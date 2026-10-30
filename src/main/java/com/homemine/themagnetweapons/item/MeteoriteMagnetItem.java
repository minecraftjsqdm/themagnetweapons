package com.homemine.themagnetweapons.item;

import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.component.Polarity;
import com.homemine.themagnetweapons.entity.MeteoriteMagnetEntity;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 陨石磁铁：16 个一组，右键投出；F7 切换极性（默认红）。
 */
public class MeteoriteMagnetItem extends Item {
    public MeteoriteMagnetItem(Properties properties) {
        super(properties);
    }

    public static Polarity polarity(ItemStack stack) {
        return stack.getOrDefault(ModComponents.POLARITY.get(), Polarity.RED);
    }

    public static ItemStack withPolarity(ItemStack stack, Polarity polarity) {
        stack.set(ModComponents.POLARITY.get(), polarity);
        return stack;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            MeteoriteMagnetEntity magnet = MeteoriteMagnetEntity.create(level, player, polarity(stack));
            magnet.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, 1.5f, 1.0f);
            level.addFreshEntity(magnet);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6f, 1.3f);
            if (!player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        player.getCooldowns().addCooldown(this, 6);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        Polarity polarity = polarity(stack);
        tooltip.add(Component.translatable("tooltip.themagnetweapons.meteorite_magnet.polarity",
                Component.translatable(polarity == Polarity.BLUE
                        ? "message.themagnetweapons.polarity.blue"
                        : "message.themagnetweapons.polarity.red")
                        .withStyle(polarity == Polarity.BLUE ? ChatFormatting.BLUE : ChatFormatting.RED)));
    }
}
