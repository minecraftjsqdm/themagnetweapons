package com.homemine.themagnetweapons.client;

import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 与基模组渲染器一致，只改飞行姿态：
 * 偏航时额外偏移 90°，让贴图平面侧对飞行方向，第一人称沿飞行方向看过去只剩一条竖线；
 * 再绕贴图法线（本地 Z 轴）自转，平面不动，侧面始终是一条竖线。
 * ItemRenderer 内部会把贴图居中到原点，因此无需额外平移。
 */
public class FlatSpinThrownPickaxeRenderer<T extends ThrownMagneticToolEntity>
        extends EntityRenderer<T> {
    private final ItemRenderer itemRenderer;

    public FlatSpinThrownPickaxeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        ItemStack stack = entity.getPickaxeStack();
        if (!stack.isEmpty()) {
            poseStack.pushPose();
            if (entity.isStuck()) {
                Direction face = entity.getStuckFace();
                Vec3 normal = Vec3.atLowerCornerOf((Vec3i) face.getNormal());
                Vec3 tilt = face.getAxis().isVertical() ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
                Vec3 head = normal.add(tilt).normalize().scale(-1.0);
                float yaw = (float) Math.toDegrees(Math.atan2(head.x, head.z));
                float pitch = (float) Math.toDegrees(Math.atan2(head.y, Math.sqrt(head.x * head.x + head.z * head.z)));
                poseStack.mulPose(Axis.YP.rotationDegrees(yaw - 90.0f));
                poseStack.mulPose(Axis.ZP.rotationDegrees(pitch));
                poseStack.translate(-0.2, 0.0, 0.0);
                poseStack.mulPose(Axis.ZP.rotationDegrees(-45.0f));
            } else if (entity.isRecallWindup()) {
                // 收回前摇：原地悬停颤抖，不再自转。
                float t = (float) entity.tickCount + partialTick;
                poseStack.mulPose(Axis.YP.rotationDegrees(entity.getYRot() + 90.0f));
                poseStack.translate(Math.sin(t * 1.9f) * 0.02f, Math.sin(t * 2.7f) * 0.02f, 0.0);
                poseStack.mulPose(Axis.ZP.rotationDegrees((float) Math.sin(t * 3.1f) * 6.0f));
            } else {
                Vec3 motion = entity.getDeltaMovement();
                float facing = motion.x * motion.x + motion.z * motion.z > 1.0E-6
                        ? (float) Math.toDegrees(Math.atan2(motion.x, motion.z))
                        : entity.getYRot();
                float spin = ((float) entity.tickCount + partialTick) * 30.0f;
                // +90° 让贴图平面侧对飞行方向：第一人称沿飞行方向看过去只剩一条竖线。
                poseStack.mulPose(Axis.YP.rotationDegrees(facing + 90.0f));
                // 绕贴图法线自转：只在贴图平面内转，侧面轮廓始终是一条竖线。
                poseStack.mulPose(Axis.ZP.rotationDegrees(spin));
            }
            this.itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, packedLight,
                    OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId());
            poseStack.popPose();
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return null;
    }
}
