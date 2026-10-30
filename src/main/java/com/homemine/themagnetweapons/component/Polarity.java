package com.homemine.themagnetweapons.component;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** 陨石磁铁的极性。 */
public enum Polarity implements StringRepresentable {
    RED("red"),
    BLUE("blue");

    public static final Codec<Polarity> CODEC = StringRepresentable.fromEnum(Polarity::values);
    public static final StreamCodec<ByteBuf, Polarity> STREAM_CODEC =
            ByteBufCodecs.idMapper(id -> values()[id], Polarity::ordinal);

    private final String name;

    Polarity(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public Polarity opposite() {
        return this == RED ? BLUE : RED;
    }
}
