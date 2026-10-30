package com.homemine.themagnetweapons.mixin;

import com.homemine.themagnetweapons.client.RecallAnimation;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在玩家模型摆完姿势后叠加回收动画（右手 + 躯体），并同步外层皮肤层。
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
    @Shadow
    public ModelPart rightSleeve;

    @Shadow
    public ModelPart jacket;

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void themagnetweapons$recallPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                             float ageInTicks, float netHeadYaw, float headPitch,
                                             CallbackInfo ci) {
        if (!(entity instanceof Player player)) {
            return;
        }
        RecallAnimation.Pose pose = RecallAnimation.poseFor(player.getUUID());
        if (pose == null) {
            return;
        }
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        model.rightArm.xRot = pose.armX();
        model.rightArm.yRot = pose.armY();
        model.body.xRot = pose.bodyX();
        model.body.zRot = pose.bodyZ();
        this.rightSleeve.copyFrom(model.rightArm);
        this.jacket.copyFrom(model.body);
    }
}
