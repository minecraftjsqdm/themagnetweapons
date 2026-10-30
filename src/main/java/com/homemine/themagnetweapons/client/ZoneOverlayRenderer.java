package com.homemine.themagnetweapons.client;

import com.homemine.themagnetweapons.net.ZoneSyncPayload;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 不稳定区域的方块面叠加层：区域内的每个暴露方块面先铺一层 15% 的浅紫色面，再叠一圈 20% 的方框边框。
 * 边框是纯色窄条，宽度固定 2/16 格，不依赖贴图，窄面片上也不会被压缩成发丝线。
 * 面片按方块实际形状（{@link VoxelShape}）裁剪，台阶、楼梯、栅栏等非完整方块只覆盖自己的表面。
 * 相邻两块面片共面的那条边两侧都不绘制，只保留外轮廓，不会出现并排双线。
 * 区域范围由 {@link ZoneSyncPayload} 同步；区域内方块变化（后来放进去的方块）由定时重建缓存补上。
 * 流体方块不绘制叠加层。
 */
public final class ZoneOverlayRenderer {
    /** 边框顶点颜色的 alpha：20% 不透明度。 */
    private static final int ALPHA = 51;
    /** 面填充色随创建区域的工具类型变化（ToolKind.ordinal）：镐青、斧橙、剑红、锹棕、锄绿、锤紫。 */
    private static final int[][] PALETTE = {
            {0x8F, 0xE3, 0xFF},
            {0xFF, 0xB1, 0x6B},
            {0xFF, 0x7A, 0x7A},
            {0xC9, 0xA1, 0x6B},
            {0x9C, 0xE8, 0x7E},
            {0xD6, 0xA8, 0xFF},
    };
    private static final int[] DEFAULT_COLOR = PALETTE[5];
    private static final int FILL_ALPHA = 38;
    /** 边框固定 2/16 格宽（世界坐标），不随面片大小缩放。 */
    private static final float BORDER = 2.0f / 16.0f;
    /** 沿面法线外移一点，避免与方块表面 z-fighting。 */
    private static final float OFFSET = 0.005f;
    /** 区域内方块会变化（玩家摆放/破坏），按间隔重建缓存。 */
    private static final long REBUILD_INTERVAL = 10L;
    /** 形状坐标与共面判断的容差。 */
    private static final float EPSILON = 1.0e-4f;
    private static final Map<ResourceKey<Level>, List<ZoneSyncPayload.ZoneBox>> ZONES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Batch> CACHE = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> BUILT_AT = new HashMap<>();
    private static final RenderType FILL_TYPE = Factory.createFill();

    /** 收到服务端同步：记录区域范围，下一次渲染时重建面片缓存。 */
    public static void accept(ZoneSyncPayload payload) {
        ResourceKey<Level> dimension = payload.dimension();
        if (payload.zones().isEmpty()) {
            ZONES.remove(dimension);
            CACHE.remove(dimension);
            BUILT_AT.remove(dimension);
            return;
        }
        ZONES.put(dimension, List.copyOf(payload.zones()));
        BUILT_AT.remove(dimension);
    }

    public static void clear() {
        ZONES.clear();
        CACHE.clear();
        BUILT_AT.clear();
    }

    private static Batch build(ClientLevel level, List<ZoneSyncPayload.ZoneBox> zones) {
        Map<BlockPos, Byte> blocks = new HashMap<>();
        for (ZoneSyncPayload.ZoneBox box : zones) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        blocks.put(new BlockPos(x, y, z), box.kind());
                    }
                }
            }
        }
        // 按面法线所在的轴 + 区域类型分组，同一轴上同色的面片才可能共面共享边。
        Map<Integer, List<FaceRect>> byAxis = new HashMap<>();
        for (Map.Entry<BlockPos, Byte> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            byte kind = entry.getValue();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || isFluid(state)) {
                continue;
            }
            VoxelShape shape = state.getShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            for (Direction face : Direction.values()) {
                if (drawsFace(level, pos, face)) {
                    collect(byAxis, pos, shape, face, kind);
                }
            }
        }
        List<Quad> fills = new ArrayList<>();
        List<Quad> borders = new ArrayList<>();
        for (List<FaceRect> rects : byAxis.values()) {
            for (FaceRect rect : rects) {
                addRect(fills, rect, 0.0f, 1.0f, 0.0f, 1.0f);
            }
            for (FaceRect rect : rects) {
                float pu = borderRatio(rect.u1 - rect.u0);
                float pv = borderRatio(rect.v1 - rect.v0);
                if (!covered(rects, rect, 0)) {
                    addRect(borders, rect, 0.0f, pu, 0.0f, 1.0f);
                }
                if (!covered(rects, rect, 1)) {
                    addRect(borders, rect, 1.0f - pu, 1.0f, 0.0f, 1.0f);
                }
                if (!covered(rects, rect, 2)) {
                    addRect(borders, rect, 0.0f, 1.0f, 0.0f, pv);
                }
                if (!covered(rects, rect, 3)) {
                    addRect(borders, rect, 0.0f, 1.0f, 1.0f - pv, 1.0f);
                }
            }
        }
        return new Batch(fills, borders);
    }

    /**
     * 只排除真正的液体方块。含水（waterlogged）方块本身也带流体状态，
     * 按流体状态判断会把台阶、楼梯、栅栏等含水方块整体跳过，水下就看不到叠加层。
     */
    private static boolean isFluid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock;
    }

    /** 方块面是否可见（邻居不是不透明满方块），只有可见面才需要叠加贴图。 */
    private static boolean drawsFace(ClientLevel level, BlockPos pos, Direction face) {
        BlockPos neighbor = pos.relative(face);
        return !level.getBlockState(neighbor).isSolidRender(level, neighbor);
    }

    /**
     * 收集该方块在该朝向上真正露出的子面：取方块形状里贴向该方向的那些盒子面，
     * 台阶、楼梯、栅栏等非完整方块因此只覆盖自己的实际表面，不会铺满整格。
     */
    private static void collect(Map<Integer, List<FaceRect>> byAxis, BlockPos pos,
                                VoxelShape shape, Direction face, byte kind) {
        int ai = indexOf(face.getAxis());
        // 面内两个轴，保持与旧实现的 u/v 对应关系。
        int i1 = ai == 0 ? 1 : 0;
        int i2 = ai == 2 ? 1 : 2;
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        float normal = positive ? OFFSET : -OFFSET;
        int[] base = {pos.getX(), pos.getY(), pos.getZ()};
        List<FaceRect> rects = byAxis.computeIfAbsent(ai * 16 + (kind + 1), key -> new ArrayList<>());
        for (AABB box : shape.toAabbs()) {
            double[] bounds = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
            float u0 = (float) bounds[i1];
            float u1 = (float) bounds[i1 + 3];
            float v0 = (float) bounds[i2];
            float v1 = (float) bounds[i2 + 3];
            if (u1 - u0 <= EPSILON || v1 - v0 <= EPSILON) {
                continue;
            }
            float plane = base[ai] + (float) bounds[ai + (positive ? 3 : 0)] + normal;
            rects.add(new FaceRect(ai, i1, i2, plane,
                    base[i1] + u0, base[i1] + u1, base[i2] + v0, base[i2] + v1, kind));
        }
    }

    /**
     * 边框宽度的比例：按固定的 2/16 格换算，窄面片上最多各占一半，保证细长方块上的边框不会变细。
     */
    private static float borderRatio(float width) {
        return Math.min(BORDER, width * 0.5f) / width;
    }

    /**
     * 矩形内取一块子区域：fu/fv 是相对该矩形的 0~1 比例。
     */
    private static void addRect(List<Quad> out, FaceRect rect, float fu0, float fu1, float fv0, float fv1) {
        float[][] corners = {{fu0, fv0}, {fu1, fv0}, {fu1, fv1}, {fu0, fv1}};
        float[] vertices = new float[12];
        for (int i = 0; i < 4; i++) {
            float u = corners[i][0];
            float v = corners[i][1];
            vertices[i * 3 + rect.ai] = rect.plane;
            vertices[i * 3 + rect.i1] = rect.u0 + (rect.u1 - rect.u0) * u;
            vertices[i * 3 + rect.i2] = rect.v0 + (rect.v1 - rect.v0) * v;
        }
        out.add(new Quad(vertices, rect.kind()));
    }

    private static int indexOf(Direction.Axis axis) {
        return axis == Direction.Axis.X ? 0 : axis == Direction.Axis.Y ? 1 : 2;
    }

    /** 该条边是否被同一平面上的另一块面片完全覆盖（共享边两侧都不画，只留外轮廓）。 */
    private static boolean covered(List<FaceRect> rects, FaceRect rect, int side) {
        for (FaceRect other : rects) {
            if (other == rect || Math.abs(other.plane - rect.plane) > EPSILON) {
                continue;
            }
            switch (side) {
                case 0:
                    if (near(other.u1, rect.u0) && contains(other.v0, other.v1, rect.v0, rect.v1)) {
                        return true;
                    }
                    break;
                case 1:
                    if (near(other.u0, rect.u1) && contains(other.v0, other.v1, rect.v0, rect.v1)) {
                        return true;
                    }
                    break;
                case 2:
                    if (near(other.v1, rect.v0) && contains(other.u0, other.u1, rect.u0, rect.u1)) {
                        return true;
                    }
                    break;
                default:
                    if (near(other.v0, rect.v1) && contains(other.u0, other.u1, rect.u0, rect.u1)) {
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPSILON;
    }

    private static boolean contains(float from, float to, float innerFrom, float innerTo) {
        return from <= innerFrom + EPSILON && to >= innerTo - EPSILON;
    }

    public static void render(RenderLevelStageEvent event) {
        // 必须在半透明（水）之前绘制：水会为非完整方块补一格水面并写入深度，
        // 画在水之后的话叠加层会被这层水面的深度整个挡掉，水下就看不到特效。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        List<ZoneSyncPayload.ZoneBox> zones = ZONES.get(level.dimension());
        if (zones == null || zones.isEmpty()) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();
        long gameTime = level.getGameTime();
        Long builtAt = BUILT_AT.get(dimension);
        if (builtAt == null || gameTime - builtAt >= REBUILD_INTERVAL) {
            CACHE.put(dimension, build(level, zones));
            BUILT_AT.put(dimension, gameTime);
        }
        Batch batch = CACHE.get(dimension);
        if (batch == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(FILL_TYPE);
        for (Quad quad : batch.fills()) {
            int[] rgb = colorFor(quad.kind());
            addQuad(consumer, matrix, camera, quad, rgb[0], rgb[1], rgb[2], FILL_ALPHA);
        }
        for (Quad quad : batch.borders()) {
            int[] rgb = colorFor(quad.kind());
            addQuad(consumer, matrix, camera, quad, rgb[0], rgb[1], rgb[2], ALPHA);
        }
        buffers.endBatch(FILL_TYPE);
    }

    /** 区域颜色：越界或旧数据回落到紫色。 */
    private static int[] colorFor(byte kind) {
        return kind >= 0 && kind < PALETTE.length ? PALETTE[kind] : DEFAULT_COLOR;
    }

    private static void addQuad(VertexConsumer consumer, Matrix4f matrix, Vec3 camera, Quad quad,
                                int red, int green, int blue, int alpha) {
        float[] vertices = quad.vertices();
        for (int i = 0; i < 4; i++) {
            consumer.addVertex(matrix,
                            vertices[i * 3] - (float) camera.x,
                            vertices[i * 3 + 1] - (float) camera.y,
                            vertices[i * 3 + 2] - (float) camera.z)
                    .setColor(red, green, blue, alpha);
        }
    }

    /** 四个顶点（世界坐标）与区域类型。 */
    private record Quad(float[] vertices, byte kind) {
    }

    /** 一个方块面上的子矩形（世界坐标）：plane 沿 ai 轴，u/v 沿 i1/i2 轴。 */
    private record FaceRect(int ai, int i1, int i2, float plane,
                            float u0, float u1, float v0, float v1, byte kind) {
    }

    /** 一帧要画的两批面片：面填充与边框。 */
    private record Batch(List<Quad> fills, List<Quad> borders) {
    }

    private static final class Factory extends RenderType {
        private Factory(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                        boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        }

        private static RenderType createFill() {
            return create("themagnetweapons_zone_fill",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    true,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setLightmapState(LIGHTMAP)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }
    }

    private ZoneOverlayRenderer() {
    }
}
