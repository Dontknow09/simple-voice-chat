package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.plugins.impl.GroupImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public class CreateGroupPacket implements Packet<CreateGroupPacket> {

    public static final CustomPacketPayload.Type<CreateGroupPacket> CREATE_GROUP = new CustomPacketPayload.Type<>(new ResourceLocation(Voicechat.MODID, "create_group"));

    private String name;
    @Nullable
    private String password;
    private Group.Type type;

    public CreateGroupPacket() {

    }

    public CreateGroupPacket(String name, @Nullable String password, Group.Type type) {
        this.name = name;
        this.password = password;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public String getPassword() {
        return password;
    }

    public Group.Type getType() {
        return type;
    }

    @Override
    public CreateGroupPacket fromBytes(FriendlyByteBuf buf) {
        name = buf.readUtf(512);
        password = null;
        if (buf.readBoolean()) {
            password = buf.readUtf(512);
        }
        type = GroupImpl.TypeImpl.fromInt(buf.readShort());
        return this;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(name, 512);
        buf.writeBoolean(password != null);
        if (password != null) {
            buf.writeUtf(password, 512);
        }
        buf.writeShort(GroupImpl.TypeImpl.toInt(type));
    }

    @Override
    public Type<CreateGroupPacket> type() {
        return CREATE_GROUP;
    }

}
