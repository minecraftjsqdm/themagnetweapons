package com.homemine.themagnetweapons.client;

import com.homemine.themagnetweapons.ModItems;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;

/**
 * 手持磁性链接（占位符）时：
 * 1. 第一人称不渲染手里的物品模型，但补画手臂；
 * 2. 在手边显示两颗绕手旋转的紫色小方块，各自拖一条逐渐变淡的曲线拖尾。
 * 特效是真正的几何体，锚点由手臂骨骼求得，绑定在手部而不是跟随视线偏移。
 *
 * <p>第一人称与第三人称都先用 {@code PlayerModel#translateToHand} 把坐标系移到手臂枢轴，
 * 再沿手臂长轴移到小臂中段，最后在垂直于长轴的平面里画轨道。因此方块始终绕着胳膊转，
 * 而不是绕着相机或某个悬空点转。</p>
 */
public final class MagneticLinkEffectRenderer {
    /** 拖尾曲线的分段数。 */
    private static final int TRAIL_SEGMENTS = 48;
    /** 拖尾绕过的总角度（弧度）。 */
    private static final double TRAIL_SWEEP = Math.PI * 0.85;
    /** 绕手臂长轴旋转的半径（需大于手臂半宽 0.25，否则方块会陷进手臂里）。 */
    private static final float ORBIT_RADIUS = 0.155f;
    /** 旋转角速度（弧度 / tick）。 */
    private static final float ORBIT_SPEED = 0.11f;
    /** 方块半边长（比原尺寸缩小 60%）。 */
    private static final float CUBE_HALF = 0.0112f;
    /** 拖尾曲线头部半宽。 */
    private static final float TRAIL_WIDTH = 0.011f;
    /** 第一人称轨道半径：必须明显大于手臂半宽 0.25，方块才会绕在胳膊外面。 */
    private static final float FP_ORBIT_RADIUS = 0.36f;
    /** 第一人称特效缩放：手就在眼前，方块与拖尾本身要小。 */
    private static final float FP_SCALE = 1.3f;
    /** 第三人称 / 其他玩家看到的特效缩放：距离远，需要放大才看得清。 */
    private static final float WORLD_SCALE = 2.0f;
    /** 绕转圆心在手臂长轴上的位置（0 = 肩，0.66 = 拳端），取小臂中段。 */
    private static final float ORBIT_CENTER_Y = 0.42f;
    private static final RenderType GLOW = Factory.createGlow();
    /** 手臂局部空间：+Y 沿手臂长轴指向拳头，XZ 平面垂直于手臂长轴。 */
    private static final Vec3 ARM_AXIS = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 ARM_FRONT = new Vec3(0.0, 0.0, 1.0);
    private static final Vec3 ARM_SIDE = new Vec3(1.0, 0.0, 0.0);

    /** 第一人称：隐藏占位符物品模型，补画手臂，并在手臂中轴上画出绕臂旋转的特效。 */
    public static void onRenderHand(RenderHandEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(ModItems.TOOL_PLACEHOLDER.get())) {
            return;
        }
        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = minecraft.player;
        if (player == null || player.isInvisible() || minecraft.level == null) {
            return;
        }
        if (!(minecraft.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) {
            return;
        }
        HumanoidArm arm = event.getHand() == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        rotateToFirstPersonArm(poseStack, arm, event.getSwingProgress(), event.getEquipProgress());
        // 走原版手臂渲染入口（含 RenderArmEvent 钩子），这样其它改手臂的模组也能正常画出手。
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(poseStack, event.getMultiBufferSource(), event.getPackedLight(), player);
        } else {
            renderer.renderLeftHand(poseStack, event.getMultiBufferSource(), event.getPackedLight(), player);
        }
        // 特效与手臂共用同一姿势空间：把手部骨骼链平移到手臂轴上，再在垂直于长轴的平面里绕臂旋转。
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        poseStack.pushPose();
        model.translateToHand(arm, poseStack);
        poseStack.translate(0.0, ORBIT_CENTER_Y, 0.0);
        double time = minecraft.level.getGameTime()
                + minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        drawEffect(poseStack, event.getMultiBufferSource(), time, 0.0, FP_ORBIT_RADIUS, FP_SCALE);
        poseStack.popPose();
        poseStack.popPose();
    }

    /** 第三人称 / 其他玩家：在玩家手臂骨骼上绘制同样的特效。 */
    public static void onRenderPlayer(RenderPlayerEvent.Post event) {
        Player player = event.getEntity();
        if (player.isInvisible() || !(player instanceof AbstractClientPlayer)) {
            return;
        }
        boolean main = player.getItemInHand(InteractionHand.MAIN_HAND).is(ModItems.TOOL_PLACEHOLDER.get());
        boolean off = player.getItemInHand(InteractionHand.OFF_HAND).is(ModItems.TOOL_PLACEHOLDER.get());
        if (!main && !off) {
            return;
        }
        if (!(event.getRenderer() instanceof PlayerRenderer renderer)) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        double time = player.level().getGameTime() + event.getPartialTick();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // 复刻 LivingEntityRenderer#render 在模型局部空间之前的变换。
        float bodyYaw = Mth.rotLerp(event.getPartialTick(), player.yBodyRotO, player.yBodyRot);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - bodyYaw));
        poseStack.scale(-1.0f, -1.0f, 1.0f);
        poseStack.scale(0.9375f, 0.9375f, 0.9375f);
        poseStack.translate(0.0, -1.501, 0.0);
        if (main) {
            drawOnArm(poseStack, model, player.getMainArm(), event.getMultiBufferSource(), time, 0.0);
        }
        if (off) {
            drawOnArm(poseStack, model, player.getMainArm().getOpposite(),
                    event.getMultiBufferSource(), time, Math.PI);
        }
        poseStack.popPose();
    }

    private static void drawOnArm(PoseStack poseStack, PlayerModel<AbstractClientPlayer> model,
                                  HumanoidArm arm, MultiBufferSource buffers, double time,
                                  double phase) {
        poseStack.pushPose();
        model.translateToHand(arm, poseStack);
        poseStack.translate(0.0, ORBIT_CENTER_Y, 0.0);
        drawEffect(poseStack, buffers, time, phase, ORBIT_RADIUS * WORLD_SCALE, WORLD_SCALE);
        poseStack.popPose();
    }

    /** 原版 ItemInHandRenderer#renderPlayerArm 的变换链。 */
    private static void rotateToFirstPersonArm(PoseStack poseStack, HumanoidArm arm,
                                               float swingProgress, float equipProgress) {
        Matrix4f matrix = poseStack.last().pose();
        float side = arm != HumanoidArm.LEFT ? 1.0f : -1.0f;
        float root = Mth.sqrt(swingProgress);
        matrix.translate(
                side * (-0.3f * Mth.sin(root * (float) Math.PI) + 0.64000005f),
                0.4f * Mth.sin(root * (float) (Math.PI * 2)) - 0.6f + equipProgress * -0.6f,
                -0.4f * Mth.sin(swingProgress * (float) Math.PI) - 0.71999997f);
        matrix.rotate(Axis.YP.rotationDegrees(side * 45.0f));
        matrix.rotate(Axis.YP.rotationDegrees(side * Mth.sin(root * (float) Math.PI) * 70.0f));
        matrix.rotate(Axis.ZP.rotationDegrees(
                side * Mth.sin(swingProgress * swingProgress * (float) Math.PI) * -20.0f));
        matrix.translate(side * -1.0f, 3.6f, 3.5f);
        matrix.rotate(Axis.ZP.rotationDegrees(side * 120.0f));
        matrix.rotate(Axis.XP.rotationDegrees(200.0f));
        matrix.rotate(Axis.YP.rotationDegrees(side * -135.0f));
        matrix.translate(side * 5.6f, 0.0f, 0.0f);
    }

    /**
     * 在当前姿势空间（原点已在小臂中段、+Y 为手臂长轴）绘制两颗绕手臂中轴旋转的方块与拖尾。
     * 轨道平面垂直于手臂长轴，因此方块是绕着胳膊转，而不是绕着相机或某个悬空点转。
     */
    private static void drawEffect(PoseStack poseStack, MultiBufferSource buffers, double time,
                                   double phaseOffset, float radius, float scale) {
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffers.getBuffer(GLOW);
        float phase = (float) (time * ORBIT_SPEED + phaseOffset);
        for (int orb = 0; orb < 2; orb++) {
            float angle = phase + orb * (float) Math.PI;
            Vec3 pos = orbitPoint(angle, radius, 0.0);
            drawTrail(consumer, matrix, angle, radius, scale);
            drawCube(consumer, matrix, pos, CUBE_HALF * scale, 224, 168, 255, 240);
            drawCube(consumer, matrix, pos, CUBE_HALF * scale * 1.6f, 196, 128, 255, 26);
        }
        if (buffers instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch(GLOW);
        }
    }

    /** 轨道上的点：绕手臂长轴按角度旋转，XZ 平面垂直于手臂。 */
    private static Vec3 orbitPoint(double angle, double radius, double lift) {
        return ARM_SIDE.scale(Math.cos(angle) * radius)
                .add(ARM_FRONT.scale(Math.sin(angle) * radius))
                .add(ARM_AXIS.scale(lift));
    }

    /** 拖尾：沿同一段轨道采样出的一条连续曲线，宽度与亮度从头到尾递减。 */
    private static void drawTrail(VertexConsumer consumer, Matrix4f matrix, float headAngle,
                                  float radius, float scale) {
        Vec3 prevA = null;
        Vec3 prevB = null;
        Vec3 prevA2 = null;
        Vec3 prevB2 = null;
        int prevAlpha = 0;
        for (int i = 0; i <= TRAIL_SEGMENTS; i++) {
            double t = i / (double) TRAIL_SEGMENTS;
            double angle = headAngle - t * TRAIL_SWEEP;
            double trailRadius = radius * (1.0 - 0.18 * t);
            double lift = 0.045 * t * scale;
            Vec3 center = orbitPoint(angle, trailRadius, lift);
            // 曲线切线：用前后相邻采样点的差分，保证整条带子朝向一致。
            double delta = 0.02;
            Vec3 ahead = orbitPoint(angle + delta, trailRadius, lift);
            Vec3 behind = orbitPoint(angle - delta, trailRadius, lift);
            Vec3 tangent = ahead.subtract(behind).normalize();
            Vec3 inPlane = tangent.cross(ARM_AXIS).normalize();
            double half = TRAIL_WIDTH * scale * Math.pow(1.0 - t, 0.6);
            int alpha = (int) (215 * Math.pow(1.0 - t, 1.6));
            Vec3 a = center.subtract(inPlane.scale(half));
            Vec3 b = center.add(inPlane.scale(half));
            Vec3 a2 = center.subtract(ARM_AXIS.scale(half));
            Vec3 b2 = center.add(ARM_AXIS.scale(half));
            if (prevA != null && alpha > 0) {
                // 两条互相垂直的带子叠成一根有厚度的光带，任何角度都看得见。
                ribbon(consumer, matrix, prevA, prevB, b, a, prevAlpha, alpha);
                ribbon(consumer, matrix, prevA2, prevB2, b2, a2, prevAlpha, alpha);
            }
            prevA = a;
            prevB = b;
            prevA2 = a2;
            prevB2 = b2;
            prevAlpha = alpha;
        }
    }

    /** 一段带渐变的拖尾带：两端透明度不同，颜色为紫色。 */
    private static void ribbon(VertexConsumer consumer, Matrix4f matrix,
                               Vec3 headA, Vec3 headB, Vec3 tailB, Vec3 tailA,
                               int headAlpha, int tailAlpha) {
        int red = 198;
        int green = 122;
        int blue = 255;
        vertex(consumer, matrix, headA, red, green, blue, headAlpha);
        vertex(consumer, matrix, headB, red, green, blue, headAlpha);
        vertex(consumer, matrix, tailB, red, green, blue, tailAlpha);
        vertex(consumer, matrix, tailA, red, green, blue, tailAlpha);
    }

    /** 实心小方块：六面用不同亮度模拟立体感。 */
    private static void drawCube(VertexConsumer consumer, Matrix4f matrix, Vec3 center,
                                 float half, int red, int green, int blue, int alpha) {
        Vec3 x = new Vec3(half, 0, 0);
        Vec3 y = new Vec3(0, half, 0);
        Vec3 z = new Vec3(0, 0, half);
        // +X / -X
        quad(consumer, matrix, center.add(x).subtract(y).subtract(z), center.add(x).add(y).subtract(z),
                center.add(x).add(y).add(z), center.add(x).subtract(y).add(z), red, green, blue, alpha);
        quad(consumer, matrix, center.subtract(x).subtract(y).add(z), center.subtract(x).add(y).add(z),
                center.subtract(x).add(y).subtract(z), center.subtract(x).subtract(y).subtract(z),
                red, green, blue, alpha);
        // +Y / -Y
        quad(consumer, matrix, center.subtract(x).add(y).subtract(z), center.add(x).add(y).subtract(z),
                center.add(x).add(y).add(z), center.subtract(x).add(y).add(z),
                Math.min(255, red + 20), Math.min(255, green + 40), blue, alpha);
        quad(consumer, matrix, center.subtract(x).subtract(y).add(z), center.add(x).subtract(y).add(z),
                center.add(x).subtract(y).subtract(z), center.subtract(x).subtract(y).subtract(z),
                red * 3 / 5, green * 3 / 5, blue * 3 / 4, alpha);
        // +Z / -Z
        quad(consumer, matrix, center.subtract(x).subtract(y).add(z), center.add(x).subtract(y).add(z),
                center.add(x).add(y).add(z), center.subtract(x).add(y).add(z), red, green, blue, alpha);
        quad(consumer, matrix, center.add(x).subtract(y).subtract(z), center.subtract(x).subtract(y).subtract(z),
                center.subtract(x).add(y).subtract(z), center.add(x).add(y).subtract(z),
                red * 4 / 5, green * 4 / 5, blue * 9 / 10, alpha);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             int red, int green, int blue, int alpha) {
        vertex(consumer, matrix, a, red, green, blue, alpha);
        vertex(consumer, matrix, b, red, green, blue, alpha);
        vertex(consumer, matrix, c, red, green, blue, alpha);
        vertex(consumer, matrix, d, red, green, blue, alpha);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Vec3 pos,
                               int red, int green, int blue, int alpha) {
        consumer.addVertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .setColor(red, green, blue, alpha);
    }

    private static final class Factory extends RenderType {
        private Factory(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                        boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        }

        private static RenderType createGlow() {
            return create("themagnetweapons_magnetic_link",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    true,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }
    }

    private MagneticLinkEffectRenderer() {
    }
}
