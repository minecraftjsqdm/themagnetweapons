package com.homemine.themagnetweapons.mixin;

import com.homemine.themagnetweapons.item.MagneticToolItem;
import net.minecraft.client.player.LocalPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版在使用物品时会把移动输入压到 20%（LocalPlayer#aiStep）。
 * 磁性武器蓄力本身已经用属性修饰符扣了 20%，两者相乘只剩 16%，
 * 这里在使用磁性工具时还原原版的输入压制，让蓄力减速正好是 20%。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "aiStep", at = @At(value = "INVOKE", opcode = Opcodes.INVOKESPECIAL,
            target = "Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V", shift = At.Shift.BEFORE))
    private void themagnetweapons$undoUseItemInputPenalty(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!player.isPassenger() && player.isUsingItem()
                && player.getUseItem().getItem() instanceof MagneticToolItem) {
            player.input.leftImpulse /= 0.2F;
            player.input.forwardImpulse /= 0.2F;
        }
    }
}
