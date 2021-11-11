package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.command.VoiceChatCommands;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.common.*;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.security.InvalidKeyException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Server extends Thread {

    private final Map<UUID, ClientConnection> connections;
    private final Map<UUID, UUID> secrets;
    private final int port;
    private final org.bukkit.Server server;
    private final VoicechatSocket socket;
    private final ProcessThread processThread;
    private final BlockingQueue<RawUdpPacket> packetQueue;
    private final PingManager pingManager;
    private final PlayerStateManager playerStateManager;
    private final GroupManager groupManager;

    public Server(int port, org.bukkit.Server server) {
        this.port = port;
        this.server = server;
        socket = PluginManager.instance().getSocketImplementation(server);
        connections = new HashMap<>();
        secrets = new HashMap<>();
        packetQueue = new LinkedBlockingQueue<>();
        pingManager = new PingManager(this);
        playerStateManager = new PlayerStateManager();
        groupManager = new GroupManager();
        setDaemon(true);
        setName("VoiceChatServerThread");
        processThread = new ProcessThread();
        processThread.start();
    }

    @Override
    public void run() {
        try {
            socket.open(port, Voicechat.SERVER_CONFIG.voiceChatBindAddress.get());
            Voicechat.LOGGER.info("Server started at port {}", socket.getLocalPort());

            while (!socket.isClosed()) {
                try {
                    packetQueue.add(socket.read());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public UUID getSecret(UUID playerUUID) {
        if (secrets.containsKey(playerUUID)) {
            return secrets.get(playerUUID);
        } else {
            UUID secret = UUID.randomUUID();
            secrets.put(playerUUID, secret);
            return secret;
        }
    }

    public void disconnectClient(UUID playerUUID) {
        connections.remove(playerUUID);
        secrets.remove(playerUUID);
        PluginManager.instance().onPlayerDisconnected(server, playerUUID);
    }

    public void close() {
        socket.close();
        processThread.close();

        PluginManager.instance().onServerStopped(server);
    }

    public boolean isClosed() {
        return !processThread.running;
    }

    private class ProcessThread extends Thread {
        private boolean running;
        private long lastKeepAlive;

        public ProcessThread() {
            running = true;
            lastKeepAlive = 0L;
            setDaemon(true);
            setName("VoiceChatPacketProcessingThread");
        }

        @Override
        public void run() {
            while (running) {
                try {
                    pingManager.checkTimeouts();
                    long keepAliveTime = System.currentTimeMillis();
                    if (keepAliveTime - lastKeepAlive > Voicechat.SERVER_CONFIG.keepAlive.get()) {
                        sendKeepAlives();
                        lastKeepAlive = keepAliveTime;
                    }

                    RawUdpPacket rawPacket = packetQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (rawPacket == null) {
                        continue;
                    }

                    NetworkMessage message;
                    try {
                        message = NetworkMessage.readPacketServer(rawPacket, Server.this);
                    } catch (IndexOutOfBoundsException | BadPaddingException | NoSuchPaddingException | IllegalBlockSizeException | InvalidKeyException e) {
                        CooldownTimer.run("failed_reading_packet", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet from {}", rawPacket.getSocketAddress());
                        });
                        continue;
                    }

                    if (System.currentTimeMillis() - message.getTimestamp() > message.getTTL()) {
                        CooldownTimer.run("ttl", () -> {
                            Voicechat.LOGGER.error("Dropping voice chat packets! Your Server might be overloaded!");
                            Voicechat.LOGGER.error("Packet queue has {} packets", packetQueue.size());
                        });
                        continue;
                    }

                    if (message.getPacket() instanceof AuthenticatePacket packet) {
                        UUID secret = secrets.get(packet.getPlayerUUID());
                        if (secret != null && secret.equals(packet.getSecret())) {
                            ClientConnection connection;
                            if (!connections.containsKey(packet.getPlayerUUID())) {
                                connection = new ClientConnection(packet.getPlayerUUID(), message.getAddress());
                                connections.put(packet.getPlayerUUID(), connection);
                                Voicechat.LOGGER.info("Successfully authenticated player {}", packet.getPlayerUUID());
                                PluginManager.instance().onPlayerConnected(server.getPlayer(packet.getPlayerUUID()));
                            } else {
                                connection = connections.get(packet.getPlayerUUID());
                            }
                            sendPacket(new AuthenticateAckPacket(), connection);
                        }
                    }

                    UUID playerUUID = message.getSender(Server.this);
                    if (playerUUID == null) {
                        continue;
                    }

                    ClientConnection conn = connections.get(playerUUID);

                    if (message.getPacket() instanceof MicPacket packet) {
                        Player player = server.getPlayer(playerUUID);
                        if (player == null) {
                            continue;
                        }
                        if (!player.hasPermission(VoiceChatCommands.SPEAK_PERMISSION)) {
                            CooldownTimer.run("muted-" + playerUUID, () -> {
                                player.sendMessage(Voicechat.translate("no_speak_permission"));
                            });
                            continue;
                        }
                        PlayerState state = playerStateManager.getState(player.getUniqueId());
                        if (state == null) {
                            continue;
                        }
                        if (!PluginManager.instance().onMicPacket(player, state, packet)) {
                            processMicPacket(player, state, packet);
                        }
                    } else if (message.getPacket() instanceof PingPacket packet) {
                        pingManager.onPongPacket(packet);
                    } else if (message.getPacket() instanceof KeepAlivePacket) {
                        conn.setLastKeepAliveResponse(System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        public void close() {
            running = false;
        }
    }

    private void processMicPacket(Player player, PlayerState state, MicPacket packet) throws Exception {
        if (state.hasGroup()) {
            processGroupPacket(state, player, packet);
            if (Voicechat.SERVER_CONFIG.openGroups.get()) {
                processProximityPacket(state, player, packet);
            }
            return;
        }
        processProximityPacket(state, player, packet);
    }

    private void processGroupPacket(PlayerState senderState, Player sender, MicPacket packet) throws Exception {
        ClientGroup group = senderState.getGroup();
        if (group == null) {
            return;
        }
        NetworkMessage soundMessage = new NetworkMessage(new GroupSoundPacket(senderState.getGameProfile().getId(), packet.getData(), packet.getSequenceNumber()));
        for (PlayerState state : playerStateManager.getStates()) {
            if (!group.equals(state.getGroup())) {
                continue;
            }
            GroupSoundPacket groupSoundPacket = new GroupSoundPacket(senderState.getGameProfile().getId(), packet.getData(), packet.getSequenceNumber());
            if (senderState.getGameProfile().getId().equals(state.getGameProfile().getId())) {
                continue;
            }
            ClientConnection connection = connections.get(state.getGameProfile().getId());
            if (connection == null) {
                continue;
            }
            Player p = server.getPlayer(senderState.getGameProfile().getId());
            if (p == null) {
                continue;
            }
            if (!PluginManager.instance().onSoundPacket(sender, senderState, p, state, groupSoundPacket)) {
                connection.send(this, soundMessage);
            }
        }
    }

    private void processProximityPacket(PlayerState state, Player player, MicPacket packet) {
        double distance = Voicechat.SERVER_CONFIG.voiceChatDistance.get();
        @Nullable ClientGroup group = state.getGroup();

        SoundPacket<?> soundPacket;
        if (player.getGameMode().equals(GameMode.SPECTATOR)) {
            if (Voicechat.SERVER_CONFIG.spectatorInteraction.get()) {
                soundPacket = new LocationSoundPacket(player.getUniqueId(), player.getEyeLocation(), packet.getData(), packet.getSequenceNumber());
            } else {
                return;
            }
        } else {
            soundPacket = new PlayerSoundPacket(player.getUniqueId(), packet.getData(), packet.getSequenceNumber(), packet.isWhispering());
        }

        broadcast(ServerWorldUtils.getPlayersInRange(player.getWorld(), player.getLocation(), distance, p -> !p.getUniqueId().equals(player.getUniqueId())), soundPacket, player, state, group);
    }

    public void broadcast(Collection<Player> players, SoundPacket<?> packet, @Nullable Player sender, @Nullable PlayerState senderState, @Nullable ClientGroup group) {
        for (Player player : players) {
            PlayerState state = playerStateManager.getState(player.getUniqueId());
            if (state == null) {
                continue;
            }
            if (state.isDisabled() || state.isDisconnected()) {
                continue;
            }
            if (state.hasGroup() && state.getGroup().equals(group)) {
                continue;
            }
            ClientConnection connection = connections.get(state.getGameProfile().getId());
            if (connection == null) {
                continue;
            }
            try {
                if (!PluginManager.instance().onSoundPacket(sender, senderState, player, state, packet)) {
                    connection.send(this, new NetworkMessage(packet));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void sendKeepAlives() throws Exception {
        long timestamp = System.currentTimeMillis();

        connections.values().removeIf(connection -> {
            if (timestamp - connection.getLastKeepAliveResponse() >= Voicechat.SERVER_CONFIG.keepAlive.get() * 10L) {
                // Don't call disconnectClient here!
                secrets.remove(connection.getPlayerUUID());
                Voicechat.LOGGER.info("Player {} timed out", connection.getPlayerUUID());
                Player player = server.getPlayer(connection.getPlayerUUID());
                if (player != null) {
                    Voicechat.LOGGER.info("Reconnecting player {}", player.getName());
                    Voicechat.SERVER.initializePlayerConnection(player);
                } else {
                    Voicechat.LOGGER.error("Reconnecting player {} failed (Could not find player)", connection.getPlayerUUID());
                }
                PluginManager.instance().onPlayerDisconnected(server, connection.getPlayerUUID());
                return true;
            }
            return false;
        });

        for (ClientConnection connection : connections.values()) {
            sendPacket(new KeepAlivePacket(), connection);
        }

    }

    public Map<UUID, ClientConnection> getConnections() {
        return connections;
    }

    public VoicechatSocket getSocket() {
        return socket;
    }

    public void sendPacket(Packet<?> packet, ClientConnection connection) throws Exception {
        connection.send(this, new NetworkMessage(packet));
    }

    public PingManager getPingManager() {
        return pingManager;
    }

    public PlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }

    public GroupManager getGroupManager() {
        return groupManager;
    }

}