package com.homemine.themagnetweapons.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 占位符记录：投掷实体 UUID + 投出时的工具物品（含耐久与附魔）。
 */
public record ThrownToolData(UUID entityId, ItemStack tool) {
    public static final Codec<ThrownToolData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("entity").forGetter(ThrownToolData::entityId),
            ItemStack.CODEC.fieldOf("tool").forGetter(ThrownToolData::tool)
    ).apply(instance, ThrownToolData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ThrownToolData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                buf.writeUUID(data.entityId());
                ItemStack.STREAM_CODEC.encode(buf, data.tool());
            },
            buf -> new ThrownToolData(buf.readUUID(), ItemStack.STREAM_CODEC.decode(buf)));
}
