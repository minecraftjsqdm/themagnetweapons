package com.homemine.themagnetweapons.net;

import com.homemine.themagnetweapons.MagneticWeapons;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 服务端把当前维度的不稳定区域范围同步给客户端，客户端据此在区域内方块表面叠加方框贴图。
 * 区域变化时才发送；区域全部过期时发送一次空列表用于清除。
 */
public record ZoneSyncPayload(ResourceKey<Level> dimension, List<ZoneBox> zones)
        implements CustomPacketPayload {
    /** 一个不稳定区域的整数包围盒（含端点）；kind 为创建该区域的工具类型，用于客户端分色。 */
    public record ZoneBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, byte kind) {
    }

    public static final CustomPacketPayload.Type<ZoneSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "zone_sync"));
    public static final StreamCodec<FriendlyByteBuf, ZoneSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeResourceLocation(payload.dimension().location());
                buf.writeVarInt(payload.zones().size());
                for (ZoneBox box : payload.zones()) {
                    buf.writeInt(box.minX());
                    buf.writeInt(box.minY());
                    buf.writeInt(box.minZ());
                    buf.writeInt(box.maxX());
                    buf.writeInt(box.maxY());
                    buf.writeInt(box.maxZ());
                    buf.writeByte(box.kind());
                }
            },
            buf -> {
                ResourceKey<Level> dimension =
                        ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
                int size = buf.readVarInt();
                List<ZoneBox> zones = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    zones.add(new ZoneBox(buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readInt(), buf.readInt(), buf.readInt(), buf.readByte()));
                }
                return new ZoneSyncPayload(dimension, zones);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
