package com.homemine.themagnetweapons.component;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.serialization.Codec;

/**
 * 磁性锄的内置农资容器：单一有序列表，容量 192（等价 3 组），
 * 按放入顺序消耗。可入袋种子 / 骨粉与魔法肥料 / 神秘农业精华。
 */
public record HoeContents(List<ItemStack> items) {
    public static final int CAPACITY = 192;
    public static final HoeContents EMPTY = new HoeContents(List.of());

    public static final Codec<HoeContents> CODEC = ItemStack.OPTIONAL_CODEC.listOf()
            .xmap(HoeContents::new, HoeContents::items);

    public static final StreamCodec<RegistryFriendlyByteBuf, HoeContents> STREAM_CODEC =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list())
                    .map(HoeContents::new, HoeContents::items);

    public HoeContents {
        items = List.copyOf(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int count() {
        int total = 0;
        for (ItemStack stack : items) {
            total += stack.getCount();
        }
        return total;
    }

    /** 可入袋：种子、骨粉、神秘农业肥料与精华。 */
    public static boolean isFarmSupply(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(Items.BONE_MEAL)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id.getPath();
        if ("mysticalagriculture".equals(id.getNamespace())) {
            return path.endsWith("_essence") || path.endsWith("_seeds")
                    || path.equals("mystical_fertilizer");
        }
        return path.endsWith("_seeds");
    }

    /** 放入一件物资，返回新的容器内容（超出容量时不接收）。 */
    public HoeContents insert(ItemStack stack) {
        if (stack.isEmpty() || count() >= CAPACITY) {
            return this;
        }
        List<ItemStack> next = new ArrayList<>(items);
        int remaining = Math.min(stack.getCount(), CAPACITY - count());
        // 先并入同类栈，保持单一有序列表。
        for (int i = 0; i < next.size() && remaining > 0; i++) {
            ItemStack existing = next.get(i);
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int move = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                next.set(i, existing.copyWithCount(existing.getCount() + move));
                remaining -= move;
            }
        }
        if (remaining > 0) {
            next.add(stack.copyWithCount(remaining));
        }
        return new HoeContents(next);
    }

    /** 取一件物资但不构造新容器（供投掷物内部消耗时复用）。 */
    @Nullable
    public static ItemStack takeWithUpdate(ItemStack tool, java.util.function.Predicate<ItemStack> predicate) {
        HoeContents contents = tool.getOrDefault(
                com.homemine.themagnetweapons.ModComponents.HOE_CONTENTS.get(), EMPTY);
        for (int i = 0; i < contents.items().size(); i++) {
            ItemStack stack = contents.items().get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            List<ItemStack> next = new ArrayList<>(contents.items());
            if (stack.getCount() > 1) {
                next.set(i, stack.copyWithCount(stack.getCount() - 1));
            } else {
                next.remove(i);
            }
            tool.set(com.homemine.themagnetweapons.ModComponents.HOE_CONTENTS.get(), new HoeContents(next));
            return stack.copyWithCount(1);
        }
        return null;
    }
}
