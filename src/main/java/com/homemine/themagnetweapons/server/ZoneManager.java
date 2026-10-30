package com.homemine.themagnetweapons.server;

import com.homemine.themagnetweapons.Enchants;
import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import com.homemine.themagnetweapons.item.MagneticToolItem;
import com.homemine.themagnetweapons.net.ZoneSyncPayload;
import com.homemine.themagnetweapons.tool.ToolKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 不稳定区域：
 * <ul>
 *   <li>尺寸按矿镐材质：铁/金 3、钻石 5、下界合金 7、末地陨铁/嫦娥玉石/末世水晶 9（边长）；</li>
 *   <li>持续 20s，期间区域范围同步给客户端，由客户端在区域内方块面上叠加方框贴图；</li>
 *   <li>矿镐插入与拔出各触发一次爆发：区域内方块累加破坏进度、区域内生物受到投掷伤害；</li>
 *   <li>稳定模式下不破坏方块，但仍造成伤害；</li>
 *   <li>相邻区域（含 1 格间隔）合并成一个连通组，爆发按合并后的范围生效。</li>
 * </ul>
 */
public final class ZoneManager {
    public static final long DURATION_TICKS = 400L;
    private static final float PICKAXE_BONUS = 0.35f;
    private static final float OTHER_BONUS = 0.25f;
    /** 镐区域：镐类方块按斧砍木头的加成推进（0.35 × 1.5）。 */
    private static final float PICKAXE_BLOCK_BONUS = PICKAXE_BONUS * 1.5f;
    /** 镐区域：非镐类方块只累加 5% 进度。 */
    private static final float PICKAXE_OTHER_BONUS = 0.05f;
    /** 镐区域：黑曜石只累加 15% 进度。 */
    private static final float PICKAXE_OBSIDIAN_BONUS = 0.15f;
    /** 单次爆发最多破坏的方块数（9³）。 */
    private static final int MAX_BREAK = 729;
    /** 每 2 tick 最多推进的方块数。 */
    private static final int JOB_BUDGET = 64;
    private static final Map<ResourceKey<Level>, List<Zone>> ZONES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Deque<BlockJob>> JOBS = new HashMap<>();
    /** 上一次同步给客户端的区域范围，只有变化时才发报文。 */
    private static final Map<ResourceKey<Level>, List<ZoneSyncPayload.ZoneBox>> SENT = new HashMap<>();

    /** 矿镐钉住方块：建区并立刻爆发一次。 */
    public static void onInsert(ThrownMagneticToolEntity pickaxe) {
        if (!(pickaxe.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack stack = pickaxe.getPickaxeStack();
        if (stack.isEmpty()) {
            return;
        }
        byte kind = kindOf(stack);
        if (kind < 0) {
            return;
        }
        BlockPos center = hitPos(pickaxe);
        Zone zone = new Zone(pickaxe.getUUID(), center, halfSize(stack), kind,
                level.getGameTime() + DURATION_TICKS, pickaxe.getAttackDamage());
        ZONES.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(zone);
        burst(level, groupOf(ZONES.get(level.dimension()), zone), stack, pickaxe.getOwner());
    }

    /** 矿镐拔出：区域保留到自然到期，但按所在连通组再爆发一次。 */
    public static void onExtract(ThrownMagneticToolEntity pickaxe) {
        if (!(pickaxe.level() instanceof ServerLevel level)) {
            return;
        }
        List<Zone> zones = ZONES.get(level.dimension());
        if (zones == null || zones.isEmpty()) {
            return;
        }
        byte kind = kindOf(pickaxe.getPickaxeStack());
        if (kind < 0) {
            return;
        }
        List<Zone> group = new ArrayList<>();
        for (Zone zone : zones) {
            if (zone.sourceId().equals(pickaxe.getUUID())) {
                group = groupOf(zones, zone);
                break;
            }
        }
        if (group.isEmpty()) {
            return;
        }
        burst(level, group, pickaxe.getPickaxeStack(), pickaxe.getOwner());
    }

    /** 某坐标是否处于不稳定区域，给挖掘速度加成用。 */
    public static float bonusAt(ServerLevel level, BlockPos pos, ItemStack tool, BlockState state) {
        List<Zone> zones = ZONES.get(level.dimension());
        if (zones == null) {
            return 0.0f;
        }
        for (Zone zone : zones) {
            if (zone.contains(pos)) {
                return tool.isCorrectToolForDrops(state) ? PICKAXE_BONUS : OTHER_BONUS;
            }
        }
        return 0.0f;
    }

    public static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (server.getTickCount() % 2 == 0) {
                processJobs(level);
            }
            ResourceKey<Level> dimension = level.dimension();
            List<Zone> zones = ZONES.get(dimension);
            if (zones == null) {
                syncZones(level, List.of());
                continue;
            }
            long now = level.getGameTime();
            List<Zone> expired = new ArrayList<>();
            zones.removeIf(zone -> {
                if (now >= zone.expireTick()) {
                    expired.add(zone);
                    return true;
                }
                return false;
            });
            // 区域过期时未完成进度归零，裂纹消失。
            for (Zone zone : expired) {
                StackingProgress.clearArea(level, zone.min(), zone.max());
            }
            if (zones.isEmpty()) {
                ZONES.remove(dimension);
                syncZones(level, List.of());
                continue;
            }
            syncZones(level, zones);
        }
    }

    public static void clearLevel(ResourceKey<Level> dimension) {
        ZONES.remove(dimension);
        SENT.remove(dimension);
        JOBS.remove(dimension);
    }

    public static void clearAll() {
        ZONES.clear();
        SENT.clear();
        JOBS.clear();
        StackingProgress.clearAll();
    }

    /** 区域范围有变化时同步给该维度的客户端，客户端据此绘制方块面叠加层。 */
    private static void syncZones(ServerLevel level, List<Zone> zones) {
        ResourceKey<Level> dimension = level.dimension();
        List<ZoneSyncPayload.ZoneBox> boxes = new ArrayList<>(zones.size());
        for (Zone zone : zones) {
            BlockPos min = zone.min();
            BlockPos max = zone.max();
            boxes.add(new ZoneSyncPayload.ZoneBox(min.getX(), min.getY(), min.getZ(),
                    max.getX(), max.getY(), max.getZ(), zone.kind()));
        }
        if (boxes.equals(SENT.getOrDefault(dimension, List.of()))) {
            return;
        }
        SENT.put(dimension, boxes);
        PacketDistributor.sendToPlayersInDimension(level, new ZoneSyncPayload(dimension, boxes));
    }

    /** 连通组：三轴中心距都不超过两边半长之和 + 1 即视为相邻。 */
    private static List<Zone> groupOf(List<Zone> zones, Zone start) {
        List<Zone> group = new ArrayList<>();
        Set<Zone> visited = new HashSet<>();
        Deque<Zone> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            Zone current = queue.poll();
            group.add(current);
            for (Zone other : zones) {
                if (visited.contains(other) || !adjacent(current, other)) {
                    continue;
                }
                visited.add(other);
                queue.add(other);
            }
        }
        return group;
    }

    private static boolean adjacent(Zone a, Zone b) {
        int limit = a.half() + b.half() + 1;
        return Math.abs(a.center().getX() - b.center().getX()) <= limit
                && Math.abs(a.center().getY() - b.center().getY()) <= limit
                && Math.abs(a.center().getZ() - b.center().getZ()) <= limit;
    }

    private static void burst(ServerLevel level, List<Zone> group, ItemStack stack, Entity owner) {
        // 稳定模式下仍然造成伤害，只是不动方块。
        boolean stable = stack.isEmpty() || StackingProgress.isStable(stack);
        ToolKind kind = kindOfEnum(stack);
        boolean mace = kind == ToolKind.MACE;
        float impact = 1.0f + 0.10f * Enchants.level(stack, Enchants.IMPACT);
        Set<LivingEntity> hurt = new HashSet<>();
        Set<BlockPos> blocks = new HashSet<>();
        for (Zone zone : group) {
            BlockPos min = zone.min();
            BlockPos max = zone.max();
            AABB box = new AABB(min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
            for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (living == owner || living instanceof Player) {
                    continue;
                }
                if (living instanceof OwnableEntity ownable && ownable.getOwner() != null) {
                    continue;
                }
                if (!hurt.add(living)) {
                    continue;
                }
                float damage = zone.damage() * (mace ? impact : 1.0f);
                boolean applied = living.hurt(level.damageSources().generic(), damage);
                if (mace) {
                    // 震波击退 + 1s 眩晕。
                    Vec3 push = living.position().subtract(Vec3.atCenterOf(zone.center()));
                    if (push.lengthSqr() < 1.0E-4) {
                        push = new Vec3(0.0, 1.0, 0.0);
                    } else {
                        push = push.normalize();
                    }
                    living.push(push.x * 0.7, 0.35, push.z * 0.7);
                    living.hurtMarked = true;
                    ThrownMagneticToolEntity.applyShockwaveDebuff(living, owner);
                    ThrownMagneticToolEntity tool = PickaxeTracker.getTool(zone.sourceId());
                    if (tool != null && applied) {
                        tool.recordShockwaveDamage(damage);
                    }
                }
            }
            if (stable || kind == null || !kind.destroysBlocks()) {
                continue;
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        pos.set(x, y, z);
                        if (!blocks.add(pos.immutable())) {
                            continue;
                        }
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0f) {
                            continue;
                        }
                        // 镐区域：玻璃类方块直接摧毁，不累计进度。
                        if (kind == ToolKind.PICKAXE && isBreakableGlass(state)) {
                            level.destroyBlock(pos.immutable(), true, owner);
                            continue;
                        }
                        // 镐受挖掘等级限制；斧挖所有可破坏方块（木类快、石类慢）。
                        if (kind == ToolKind.PICKAXE && !canHarvest(stack, state)) {
                            continue;
                        }
                        // 对应方块位置播放该方块的破坏特效，稳定模式同样有反馈。
                        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                                x + 0.5, y + 0.5, z + 0.5, 8, 0.45, 0.45, 0.45, 0.25);
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            return;
        }
        // 按到区域中心的距离由近到远推进；每次爆发最多破坏 729 块。
        List<BlockPos> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator.comparingDouble(pos -> nearestDistSq(pos, group)));
        Deque<BlockJob> jobs = JOBS.computeIfAbsent(level.dimension(), key -> new ArrayDeque<>());
        UUID ownerId = owner == null ? null : owner.getUUID();
        UUID sourceId = group.get(0).sourceId();
        int[] counter = new int[1];
        for (BlockPos pos : ordered) {
            BlockState state = level.getBlockState(pos);
            float bonus = bonusFor(kind, stack, state);
            jobs.add(new BlockJob(pos, bonus, ownerId, sourceId, stack.copy(), counter));
        }
    }

    private static void processJobs(ServerLevel level) {
        Deque<BlockJob> jobs = JOBS.get(level.dimension());
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        int budget = JOB_BUDGET;
        while (budget-- > 0 && !jobs.isEmpty()) {
            BlockJob job = jobs.poll();
            BlockPos pos = job.pos();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0f) {
                continue;
            }
            boolean allow = job.counter()[0] < MAX_BREAK;
            Entity owner = job.ownerId() == null ? null : level.getPlayerByUUID(job.ownerId());
            boolean full = StackingProgress.apply(level, pos, state, job.bonus(), owner, job.tool(), allow);
            if (full && allow) {
                job.counter()[0]++;
                ThrownMagneticToolEntity tool = PickaxeTracker.getTool(job.sourceId());
                if (tool != null) {
                    tool.onBlockDestroyed();
                }
            }
        }
        if (jobs.isEmpty()) {
            JOBS.remove(level.dimension());
        }
    }

    private static double nearestDistSq(BlockPos pos, List<Zone> group) {
        double best = Double.MAX_VALUE;
        for (Zone zone : group) {
            best = Math.min(best, pos.distSqr(zone.center()));
        }
        return best;
    }

    /** 斧：木类 ×1.5、其他 ×1.05；镐：镐类同斧砍木头、非镐类 5%、黑曜石 15%。 */
    private static float bonusFor(ToolKind kind, ItemStack stack, BlockState state) {
        if (kind == ToolKind.PICKAXE) {
            if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)) {
                return PICKAXE_OBSIDIAN_BONUS;
            }
            return stack.isCorrectToolForDrops(state) ? PICKAXE_BLOCK_BONUS : PICKAXE_OTHER_BONUS;
        }
        float base = stack.isCorrectToolForDrops(state) ? PICKAXE_BONUS : OTHER_BONUS;
        if (kind == ToolKind.AXE) {
            return base * (state.is(BlockTags.MINEABLE_WITH_AXE) ? 1.5f : 1.05f);
        }
        return base;
    }

    /**
     * 可被镐区域直接摧毁的玻璃：原版玻璃、染色玻璃、玻璃板、染色玻璃板及其子类。
     * 原版玻璃与玻璃板按方块精确匹配；不能用 TransparentBlock 判定，否则会误伤遮光玻璃与 Create 等模组的玻璃。
     */
    private static boolean isBreakableGlass(BlockState state) {
        return state.is(Blocks.GLASS)
                || state.getBlock() instanceof StainedGlassBlock
                || state.getBlock() instanceof StainedGlassPaneBlock
                || state.is(Blocks.GLASS_PANE);
    }

    private static ToolKind kindOfEnum(ItemStack stack) {
        return stack.getItem() instanceof MagneticToolItem tool ? tool.kind() : null;
    }

    /** 矿镐等级是否够收获该方块：无收获要求的方块（泥土、木材等）一律允许。 */
    private static boolean canHarvest(ItemStack stack, BlockState state) {
        return !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
    }

    private static BlockPos hitPos(ThrownMagneticToolEntity pickaxe) {
        Direction face = pickaxe.getStuckFace();
        Vec3 normal = Vec3.atLowerCornerOf((Vec3i) face.getNormal());
        return BlockPos.containing(pickaxe.position().add(normal.scale(-0.5)));
    }

    /** 区域类型编码：取 ToolKind 序号；-1 表示该类工具不建区域。 */
    private static byte kindOf(ItemStack stack) {
        if (stack.getItem() instanceof MagneticToolItem tool) {
            return tool.kind().buildsZone() ? (byte) tool.kind().ordinal() : (byte) -1;
        }
        return -1;
    }

    private static int halfSize(ItemStack stack) {
        return stack.getItem() instanceof MagneticToolItem tool ? tool.zoneHalf() : 2;
    }

    private record Zone(UUID sourceId, BlockPos center, int half, byte kind, long expireTick, float damage) {
        boolean contains(BlockPos pos) {
            return Math.abs(pos.getX() - center.getX()) <= half
                    && Math.abs(pos.getY() - center.getY()) <= half
                    && Math.abs(pos.getZ() - center.getZ()) <= half;
        }

        BlockPos min() {
            return center.offset(-half, -half, -half);
        }

        BlockPos max() {
            return center.offset(half, half, half);
        }
    }

    private record BlockJob(BlockPos pos, float bonus, UUID ownerId, UUID sourceId, ItemStack tool,
                            int[] counter) {
    }

    private ZoneManager() {
    }
}
