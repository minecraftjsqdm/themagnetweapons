package com.homemine.themagnetweapons.net;

import com.homemine.themagnetweapons.MagneticWeapons;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端左键手持占位符时发给服务端，切换冲刺 / 滑模。
 */
public record DashTogglePayload() implements CustomPacketPayload {
    public static final DashTogglePayload INSTANCE = new DashTogglePayload();
    public static final CustomPacketPayload.Type<DashTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "dash_toggle"));
    public static final StreamCodec<FriendlyByteBuf, DashTogglePayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
