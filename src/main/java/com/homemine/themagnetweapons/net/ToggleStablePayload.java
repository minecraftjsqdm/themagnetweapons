package com.homemine.themagnetweapons.net;

import com.homemine.themagnetweapons.MagneticWeapons;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端按下稳定器快捷键后发给服务端。
 */
public record ToggleStablePayload() implements CustomPacketPayload {
    public static final ToggleStablePayload INSTANCE = new ToggleStablePayload();
    public static final CustomPacketPayload.Type<ToggleStablePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "toggle_stable"));
    public static final StreamCodec<FriendlyByteBuf, ToggleStablePayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
