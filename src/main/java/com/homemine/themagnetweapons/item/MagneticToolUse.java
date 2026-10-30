package com.homemine.themagnetweapons.item;

import com.homemine.themagnetweapons.Enchants;
import com.homemine.themagnetweapons.MagneticWeapons;
import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.ModItems;
import com.homemine.themagnetweapons.component.HoeContents;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import com.homemine.themagnetweapons.tool.ToolKind;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

/**
 * 磁性工具的公共逻辑：属性、投掷、召回与 tooltip。
 */
public final class MagneticToolUse {
    public static final int MIN_CHARGE = 10;
    public static final int USE_DURATION = 72000;

    private static final ResourceLocation DAMAGE_ID =
            ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "base_attack_damage");
    private static final ResourceLocation SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "base_attack_speed");
    /** 蓄力期间的移速惩罚修饰符。 */
    private static final ResourceLocation CHARGE_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "charge_slowdown");
    private static final double CHARGE_SLOWDOWN = -0.20;

    public static ItemAttributeModifiers attributes(Tier tier, ToolKind kind, float attackDamage) {
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(DAMAGE_ID, attackDamage, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(SPEED_ID, kind.attackSpeed(), AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    public static InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        applyChargeSlowdown(player);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    /** 蓄力时移速降低 20%。 */
    public static void applyChargeSlowdown(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.getModifier(CHARGE_SPEED_ID) != null) {
            return;
        }
        speed.addTransientModifier(new AttributeModifier(CHARGE_SPEED_ID, CHARGE_SLOWDOWN,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    public static void clearChargeSlowdown(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(CHARGE_SPEED_ID);
        }
    }

    public static UseAnim animation() {
        return UseAnim.SPEAR;
    }

    public static void release(ItemStack stack, Level level, LivingEntity entity, int timeLeft,
                               MagneticToolItem tool) {
        if (!(entity instanceof Player player)) {
            return;
        }
        clearChargeSlowdown(player);
        int charge = USE_DURATION - timeLeft;
        if (charge < MIN_CHARGE) {
            // 短按：磁性锄存取农资；其他工具忽略。
            if (tool.kind() == ToolKind.HOE && !level.isClientSide) {
                handleHoeStorage(stack, player);
            }
            return;
        }
        if (level.isClientSide) {
            return;
        }
        ItemStack thrownStack = stack.copy();
        thrownStack.setCount(1);
        ThrownMagneticToolEntity thrown = ThrownMagneticToolEntity.create(level, player, thrownStack);
        float power = Math.min(charge / 20.0f, 1.0f);
        float velocity = tool.kind().throwBase() + power * tool.kind().throwPower();
        velocity *= 1.0f + 0.07f * Enchants.level(thrownStack, Enchants.REACH);
        thrown.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, velocity, 0.5f);
        level.addFreshEntity(thrown);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.0f, 1.0f);
        ItemStack placeholder = new ItemStack(ModItems.TOOL_PLACEHOLDER.get());
        placeholder.set(ModComponents.THROWN_TOOL.get(),
                new ThrownToolData(thrown.getUUID(), thrownStack));
        int modelIndex = ModItems.placeholderModelIndex(thrownStack);
        if (modelIndex > 0) {
            placeholder.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(modelIndex));
        }
        player.setItemInHand(player.getUsedItemHand(), placeholder);
        player.getCooldowns().addCooldown(ModItems.TOOL_PLACEHOLDER.get(), tool.kind().cooldown());
    }

    public static void appendTooltip(ItemStack stack, List<Component> tooltip) {
        if (Enchants.has(stack, Enchants.STABILIZER)) {
            boolean stable = stack.getOrDefault(ModComponents.STABLE.get(), false);
            MutableComponent line = stable
                    ? Component.translatable("tooltip.themagnetweapons.stable.on")
                    : Component.translatable("tooltip.themagnetweapons.stable.off",
                            MagneticWeapons.stableKeyHint.get());
            tooltip.add(line.withStyle(stable ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
    }

    /** 磁性锄：短按右键存入农资，Shift + 短按取出一件。 */
    private static void handleHoeStorage(ItemStack stack, Player player) {
        HoeContents contents = stack.getOrDefault(ModComponents.HOE_CONTENTS.get(), HoeContents.EMPTY);
        if (player.isShiftKeyDown()) {
            ItemStack out = HoeContents.takeWithUpdate(stack, s -> true);
            if (out == null) {
                return;
            }
            if (!player.getInventory().add(out)) {
                player.drop(out, false);
            }
            player.level().playSound(null, player.blockPosition(), SoundEvents.BUNDLE_REMOVE_ONE,
                    SoundSource.PLAYERS, 1.0f, 1.0f);
            return;
        }
        HoeContents updated = contents;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && updated.count() < HoeContents.CAPACITY; i++) {
            ItemStack slot = inventory.getItem(i);
            if (!HoeContents.isFarmSupply(slot)) {
                continue;
            }
            int before = updated.count();
            updated = updated.insert(slot);
            int moved = updated.count() - before;
            if (moved > 0) {
                slot.shrink(moved);
                if (slot.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        if (updated.count() != contents.count()) {
            stack.set(ModComponents.HOE_CONTENTS.get(), updated);
            player.level().playSound(null, player.blockPosition(), SoundEvents.BUNDLE_INSERT,
                    SoundSource.PLAYERS, 1.0f, 1.0f);
        }
    }

    private MagneticToolUse() {
    }
}
