package com.homemine.themagnetweapons.entity;

import com.homemine.themagnetweapons.Enchants;
import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.ModEntities;
import com.homemine.themagnetweapons.component.HoeContents;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.homemine.themagnetweapons.item.MagneticToolItem;
import com.homemine.themagnetweapons.item.ToolPlaceholderItem;
import com.homemine.themagnetweapons.server.LoyaltyHandler;
import com.homemine.themagnetweapons.tool.ToolKind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 通用磁性投掷物：自带同步数据、飞行 / 命中 / 回收骨架，
 * 按工具类型分支处理吸附、滑行、掀地、犁地与重锤落差伤害。
 */
public class ThrownMagneticToolEntity extends Projectile {
    private static final EntityDataAccessor<ItemStack> DATA_TOOL =
            SynchedEntityData.defineId(ThrownMagneticToolEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> DATA_STUCK =
            SynchedEntityData.defineId(ThrownMagneticToolEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_RETURNING =
            SynchedEntityData.defineId(ThrownMagneticToolEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_FACE =
            SynchedEntityData.defineId(ThrownMagneticToolEntity.class, EntityDataSerializers.INT);
    /** 链接失效满 30 s 后同步给客户端，客户端才能瞄准并右键捡起。 */
    private static final EntityDataAccessor<Boolean> DATA_PICKABLE =
            SynchedEntityData.defineId(ThrownMagneticToolEntity.class, EntityDataSerializers.BOOLEAN);
    /** 回收阶段：0 = 未回收，1 = 前摇，2 = 回飞。客户端据此播放玩家与武器动画。 */
    private static final EntityDataAccessor<Integer> DATA_RECALL =
            SynchedEntityData.defineId(ThrownMagneticToolEntity.class, EntityDataSerializers.INT);
    /** 出手后多少 tick 强制回收，同时也是链接失效后可被捡起的最短时间。 */
    public static final int LINK_TICKS = 600;
    /** 收回前摇 tick 数：武器原地不动，玩家抬手并挥到侧后方。 */
    public static final int RECALL_WINDUP_TICKS = 5;
    /** 前摇 + 回飞的总 tick 上限，15 tick = 0.75 s（50 m 距离内）。 */
    public static final int RECALL_TOTAL_TICKS = 15;
    private static final double DRAG = 0.99;
    private static final double TERMINAL_VELOCITY = 3.92;
    private static final double PARENT_GRAVITY = 0.03;
    private static final float DIRECT_HIT_STOP = 0.2f;
    private static final float SHOVEL_SPEED_FLOOR = 0.05f;
    private static final String[] FARM_TIERS =
            {"inferium", "prudentium", "tertium", "imperium", "supremium"};

    private float toolDamage = 4.0f;
    private double launchY;
    private double launchX;
    private double launchZ;
    private int lifeTicks;
    private int recallTicks;
    private boolean customReturning;
    /** 磁性链接是否已失效（占位符已消失，不能再召回）。 */
    private boolean linkBroken;
    /** 本次投掷是否已经为「命中方块」扣过耐久（每次投掷只算一次）。 */
    private boolean hitBlockSpent;

    /** 剑 / 锄的贴地滑行状态。 */
    private boolean sliding;
    private double slideSpeed;
    private double slideDecel;
    private double slideDirX;
    private double slideDirZ;

    /** 锹：每破坏一个方块扣掉的速度。 */
    private double shovelDecel;

    private final Set<UUID> flightHit = new HashSet<>();
    private final Set<UUID> slideHit = new HashSet<>();
    private final Set<UUID> returnHit = new HashSet<>();
    private final Set<BlockPos> hoeVisited = new HashSet<>();

    public ThrownMagneticToolEntity(EntityType<? extends ThrownMagneticToolEntity> type, Level level) {
        super(type, level);
    }

    public static ThrownMagneticToolEntity create(Level level, Player owner, ItemStack stack) {
        ThrownMagneticToolEntity thrown =
                new ThrownMagneticToolEntity(ModEntities.THROWN_MAGNETIC_TOOL.get(), level);
        thrown.setOwner(owner);
        thrown.setPos(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
        thrown.setPickaxeStack(stack);
        if (stack.getItem() instanceof MagneticToolItem tool) {
            thrown.toolDamage = tool.attackDamage();
            int budget = tool.tierDef().shovelBlocks();
            thrown.shovelDecel = (2.5 - SHOVEL_SPEED_FLOOR) / Math.max(1, budget);
        }
        thrown.launchY = owner.getY();
        thrown.launchX = thrown.getX();
        thrown.launchZ = thrown.getZ();
        return thrown;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TOOL, new ItemStack(Items.IRON_PICKAXE));
        builder.define(DATA_STUCK, false);
        builder.define(DATA_RETURNING, false);
        builder.define(DATA_FACE, Direction.UP.get3DDataValue());
        builder.define(DATA_PICKABLE, false);
        builder.define(DATA_RECALL, 0);
    }

    /** 0 = 未回收，1 = 前摇，2 = 回飞。 */
    public int getRecallPhase() {
        return entityData.get(DATA_RECALL);
    }

    private void setRecallPhase(int phase) {
        if (entityData.get(DATA_RECALL) != phase) {
            entityData.set(DATA_RECALL, phase);
        }
    }

    /** 武器处于回收前摇：原地静止并轻微颤抖。 */
    public boolean isRecallWindup() {
        return entityData.get(DATA_RECALL) == 1;
    }

    public ItemStack getPickaxeStack() {
        return entityData.get(DATA_TOOL);
    }

    public void setPickaxeStack(ItemStack stack) {
        if (!stack.isEmpty()) {
            entityData.set(DATA_TOOL, stack.copyWithCount(1));
        }
    }

    public boolean isStuck() {
        return entityData.get(DATA_STUCK);
    }

    public void setStuck(boolean stuck) {
        entityData.set(DATA_STUCK, stuck);
    }

    public boolean isReturning() {
        return entityData.get(DATA_RETURNING);
    }

    public void setReturning(boolean returning) {
        entityData.set(DATA_RETURNING, returning);
    }

    public boolean isLinkBroken() {
        return linkBroken;
    }

    /** 出手后的存活 tick 数，由服务端跟踪器在实体不被 tick 时继续递增。 */
    public int getLifeTicks() {
        return lifeTicks;
    }

    public void setLifeTicks(int ticks) {
        lifeTicks = Math.max(0, ticks);
    }

    /** 只有链接失效且出手满 30 s 的武器才允许被瞄准、拾取。 */
    @Override
    public boolean isPickable() {
        return entityData.get(DATA_PICKABLE);
    }

    public Direction getStuckFace() {
        return Direction.from3DDataValue(entityData.get(DATA_FACE));
    }

    public void setStuckFace(Direction face) {
        entityData.set(DATA_FACE, face.get3DDataValue());
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return target != getOwner() && super.canHitEntity(target);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Tool", getPickaxeStack().save(registryAccess(), new CompoundTag()));
        tag.putBoolean("Stuck", isStuck());
        tag.putBoolean("Returning", isReturning());
        tag.putFloat("AttackDamage", toolDamage);
        tag.putInt("StuckFace", getStuckFace().get3DDataValue());
        tag.putInt("LifeTicks", lifeTicks);
        tag.putInt("RecallTicks", recallTicks);
        tag.putBoolean("CustomReturning", customReturning);
        tag.putBoolean("LinkBroken", linkBroken);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Tool")) {
            ItemStack.parse(registryAccess(), tag.getCompound("Tool")).ifPresent(this::setPickaxeStack);
        }
        setStuck(tag.getBoolean("Stuck"));
        setReturning(tag.getBoolean("Returning"));
        if (tag.contains("StuckFace")) {
            setStuckFace(Direction.from3DDataValue(tag.getInt("StuckFace")));
        }
        if (tag.contains("AttackDamage")) {
            toolDamage = tag.getFloat("AttackDamage");
        }
        lifeTicks = tag.getInt("LifeTicks");
        recallTicks = tag.getInt("RecallTicks");
        customReturning = tag.getBoolean("CustomReturning");
        linkBroken = tag.getBoolean("LinkBroken");
        if (linkBroken && lifeTicks >= LINK_TICKS) {
            setPickable(true);
        }
        if (customReturning) {
            setRecallPhase(recallTicks < RECALL_WINDUP_TICKS ? 1 : 2);
        }
        if (isStuck() || isReturning() || customReturning) {
            setNoGravity(true);
        }
    }

    public float getAttackDamage() {
        return toolDamage;
    }

    @Nullable
    public ToolKind kind() {
        return getPickaxeStack().getItem() instanceof MagneticToolItem tool ? tool.kind() : null;
    }

    @Nullable
    public MagneticToolItem tool() {
        return getPickaxeStack().getItem() instanceof MagneticToolItem tool ? tool : null;
    }

    @Override
    public void tick() {
        lifeTicks++;
        if (!level().isClientSide) {
            tickLink();
        }
        if (customReturning || getRecallPhase() > 0) {
            this.baseTick();
            if (!level().isClientSide) {
                tickCustomReturn();
            }
            return;
        }
        ToolKind kind = kind();
        if (sliding && (kind == ToolKind.SWORD || kind == ToolKind.HOE)) {
            tickSlide(kind);
            return;
        }
        if (kind == ToolKind.SHOVEL && !isStuck()) {
            shovelFlightBreak();
        }
        if (kind == ToolKind.AXE && !isStuck()) {
            axeClearLeaves();
        }
        baseTick();
        if (!level().isClientSide) {
            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            if (hit.getType() != HitResult.Type.MISS) {
                hitTargetOrDeflectSelf(hit);
            }
        }
        if (customReturning || isRemoved() || isReturning()) {
            return;
        }
        if (!isStuck()) {
            move(MoverType.SELF, getDeltaMovement());
            Vec3 flight = getDeltaMovement();
            setDeltaMovement(flight.x * DRAG, flight.y * DRAG - PARENT_GRAVITY, flight.z * DRAG);
            updateRotation();
        }
        if (kind == ToolKind.MACE && !isStuck()) {
            Vec3 motion = getDeltaMovement();
            double extra = kind.gravity() - PARENT_GRAVITY;
            double y = Math.max(motion.y - extra, -TERMINAL_VELOCITY);
            setDeltaMovement(motion.x, y, motion.z);
        }
    }

    /**
     * 磁性链接的每 tick 维护：
     * 1. 出手满 30 s 无条件强制回收；
     * 2. 链接失效后再过 30 s 才开放拾取。
     */
    private void tickLink() {
        if (linkBroken) {
            if (lifeTicks >= LINK_TICKS) {
                setPickable(true);
            }
            return;
        }
        if (lifeTicks >= LINK_TICKS) {
            if (!customReturning) {
                startRecall();
            }
            return;
        }
        if (!(getOwner() instanceof Player player) || !player.isAlive()
                || player.level() != level()) {
            breakLink();
        }
    }

    /** 磁性链接失效：占位符直接消失；附魔忠诚则立即返回并损失耐久。 */
    public void breakLink() {
        breakLink(getOwner() instanceof Player player ? player : null);
    }

    public void breakLink(@Nullable Player player) {
        if (linkBroken) {
            return;
        }
        linkBroken = true;
        if (player != null) {
            LoyaltyHandler.onLinkBroken(this, player);
        }
    }

    public void setPickable(boolean pickable) {
        if (entityData.get(DATA_PICKABLE) != pickable) {
            entityData.set(DATA_PICKABLE, pickable);
        }
    }

    /** 链接失效满 30 s 后，任何玩家空手右键都能把武器捡走。 */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!entityData.get(DATA_PICKABLE) || !player.getItemInHand(hand).isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide) {
            ItemStack tool = getPickaxeStack().copy();
            if (!player.getInventory().add(tool)) {
                player.drop(tool, false);
            }
            level().playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP,
                    SoundSource.PLAYERS, 0.5f, 1.0f);
            discard();
        }
        return InteractionResult.SUCCESS;
    }

    private static double sqr(double value) {
        return value * value;
    }

    private void stopHere() {
        sliding = false;
        setDeltaMovement(Vec3.ZERO);
        setStuck(true);
        setNoGravity(true);
    }

    // ------------------------------------------------------------------ 命中分派

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        if (level().isClientSide) {
            return;
        }
        ToolKind kind = kind();
        if (kind == null) {
            return;
        }
        switch (kind) {
            case PICKAXE -> stick(hit, true);
            case AXE -> {
                // 斧劈中树叶：直接摧毁并继续飞行，不吸附。
                if (level().getBlockState(hit.getBlockPos()).is(BlockTags.LEAVES)) {
                    level().destroyBlock(hit.getBlockPos(), true, getOwner());
                } else {
                    stick(hit, true);
                }
            }
            case MACE -> maceHit(hit);
            case SWORD, HOE -> startSlide();
            case SHOVEL -> shovelHitBlock(hit);
        }
    }

    /** 碰撞箱角点射线补测命中时由 ThrownPickaxeEdgeFix 回调，走同一套命中分派。 */
    public void handleSweptHit(BlockHitResult hit) {
        if (!level().isClientSide) {
            onHitBlock(hit);
        }
    }

    private void stick(BlockHitResult hit, boolean durability) {
        Vec3 location = hit.getLocation();
        setPos(location.x, location.y, location.z);
        setDeltaMovement(Vec3.ZERO);
        setNoGravity(true);
        setStuck(true);
        setStuckFace(hit.getDirection());
        level().playSound(null, hit.getBlockPos(), SoundEvents.TRIDENT_HIT_GROUND,
                SoundSource.BLOCKS, 1.0f, 1.0f);
        if (durability) {
            spendHitBlockDurability();
        }
    }

    /** 命中方块：每次投掷只扣 1 点耐久。 */
    private void spendHitBlockDurability() {
        if (hitBlockSpent) {
            return;
        }
        hitBlockSpent = true;
        damageTool(1);
    }

    /** 剑 / 锄：落地后进入贴地滑行，不吸附、不被冻结。 */
    private void startSlide() {
        Vec3 motion = getDeltaMovement();
        double v0 = Math.hypot(motion.x, motion.z);
        if (v0 < 0.02) {
            stopHere();
            return;
        }
        sliding = true;
        slideSpeed = v0;
        slideDecel = v0 / 13.0;
        slideDirX = motion.x / v0;
        slideDirZ = motion.z / v0;
        slideHit.clear();
        hoeVisited.clear();
        setStuck(false);
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
    }

    private void tickSlide(ToolKind kind) {
        this.baseTick();
        if (isRemoved()) {
            return;
        }
        if (slideSpeed <= 0.001) {
            stopHere();
            return;
        }
        Vec3 from = position();
        Vec3 to = from.add(slideDirX * slideSpeed, 0.0, slideDirZ * slideSpeed);
        BlockHitResult clip = level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 target = to;
        if (clip.getType() != HitResult.Type.MISS) {
            Vec3 normal = Vec3.atLowerCornerOf(clip.getDirection().getNormal());
            target = clip.getLocation().add(normal.scale(0.1));
            if (clip.getDirection().getAxis().isHorizontal()) {
                // 撞到方块侧面：只反转法向分量，速度大小不变。
                if (clip.getDirection().getAxis() == Direction.Axis.X) {
                    slideDirX = -slideDirX;
                } else {
                    slideDirZ = -slideDirZ;
                }
                level().playSound(null, clip.getBlockPos(), SoundEvents.TRIDENT_HIT,
                        SoundSource.BLOCKS, 0.4f, 1.2f);
            }
        }
        setPos(target.x, getY(), target.z);
        if (kind == ToolKind.SWORD) {
            swordSlideDamage();
        } else {
            hoeSlideOps();
        }
        slideSpeed = Math.max(0.0, slideSpeed - slideDecel);
        BlockPos below = BlockPos.containing(getX(), getY() - 0.2, getZ());
        if (level().getBlockState(below).getCollisionShape(level(), below).isEmpty()) {
            // 掉边缘：恢复重力，落地后按新水平分速度重新滑行。
            sliding = false;
            setNoGravity(false);
            setDeltaMovement(slideDirX * slideSpeed, 0.0, slideDirZ * slideSpeed);
        }
        if (slideSpeed <= 0.001) {
            stopHere();
        }
    }

    private void swordSlideDamage() {
        List<Entity> targets = level().getEntities(this, getBoundingBox().inflate(0.75),
                e -> e instanceof LivingEntity);
        for (Entity entity : targets) {
            if (!(entity instanceof LivingEntity living) || living == getOwner()) {
                continue;
            }
            if (living instanceof OwnableEntity ownable && ownable.getOwner() != null) {
                continue;
            }
            if (!slideHit.add(living.getUUID())) {
                continue;
            }
            hurtLiving(living, toolDamage * kind().directMultiplier() * 0.8f);
            damageTool(1);
        }
    }

    // ------------------------------------------------------------------ 斧

    /** 斧飞行途中直接劈掉路径上的树叶，不吸附、不减速。 */
    private void axeClearLeaves() {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 motion = getDeltaMovement();
        if (motion.lengthSqr() < 1.0E-6) {
            return;
        }
        AABB sweep = getBoundingBox().expandTowards(motion.x, motion.y, motion.z).inflate(0.25);
        BlockPos min = BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ);
        BlockPos max = BlockPos.containing(sweep.maxX, sweep.maxY, sweep.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!server.getBlockState(pos).is(BlockTags.LEAVES)) {
                continue;
            }
            server.destroyBlock(pos.immutable(), true, getOwner());
        }
    }

    // ------------------------------------------------------------------ 锹

    /** 锹飞行破坏：半径 1.5，每块扣固定速度，速度低于阈值即停止破坏。 */
    private void shovelFlightBreak() {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        if (getDeltaMovement().length() <= SHOVEL_SPEED_FLOOR) {
            return;
        }
        ItemStack stack = getPickaxeStack();
        Entity owner = getOwner();
        BlockPos center = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (getDeltaMovement().length() <= SHOVEL_SPEED_FLOOR) {
                return;
            }
            if (pos.distSqr(center) > 2.25) {
                continue;
            }
            BlockState state = server.getBlockState(pos);
            if (!canShovelBreak(server, pos, state, stack)) {
                continue;
            }
            server.destroyBlock(pos.immutable(), true, owner);
            spendShovelSpeed();
            onBlockBroken();
        }
    }

    private boolean canShovelBreak(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0f) {
            return false;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return true;
        }
        if (!state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || Enchants.level(stack, Enchants.PRECISION) <= 0) {
            return false;
        }
        // 精锐：镐可破坏方块受挖掘等级限制（钻石及以上才能挖黑曜石这类高硬度方块）。
        if (state.getDestroySpeed(level, pos) >= 30.0f) {
            return stack.getItem() instanceof MagneticToolItem tool
                    && tool.tierDef().ordinal() >= com.homemine.themagnetweapons.tool.TierDef.DIAMOND.ordinal();
        }
        return true;
    }

    private void spendShovelSpeed() {
        Vec3 motion = getDeltaMovement();
        double speed = motion.length();
        if (speed < 1.0E-5) {
            return;
        }
        double next = Math.max(0.0, speed - shovelDecel);
        setDeltaMovement(next < 1.0E-5 ? Vec3.ZERO : motion.scale(next / speed));
    }

    private void shovelHitBlock(BlockHitResult hit) {
        BlockState state = level().getBlockState(hit.getBlockPos());
        if (state.getDestroySpeed(level(), hit.getBlockPos()) < 0.0f) {
            // 无法破坏的方块：叮一声立即停下，生成区域继续工作。
            level().playSound(null, hit.getBlockPos(), SoundEvents.ANVIL_LAND,
                    SoundSource.BLOCKS, 0.6f, 1.6f);
            stick(hit, true);
            return;
        }
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        setPos(hit.getLocation().add(normal.scale(0.1)));
        if (getDeltaMovement().length() > 0.01) {
            spendHitBlockDurability();
        }
    }

    /** 区域每破坏一个方块：30% 概率扣 1 点耐久，随后独立结算汲取。 */
    public void onBlockDestroyed() {
        if (random.nextFloat() < 0.30f) {
            damageTool(1);
        }
        maybeSiphon(getPickaxeStack());
    }

    private void onBlockBroken() {
        onBlockDestroyed();
    }

    // ------------------------------------------------------------------ 锄

    /** 锄滑行：每个方块只结算一次，顺序为收割补种 → 耕地化 → 播种 → 催熟 → 升级。 */
    private void hoeSlideOps() {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        BlockPos center = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 1, 2))) {
            BlockPos immutable = pos.immutable();
            if (immutable.distSqr(center) > 8.0) {
                continue;
            }
            if (hoeVisited.add(immutable)) {
                hoeInteract(server, immutable);
            }
        }
    }

    private void hoeInteract(ServerLevel level, BlockPos pos) {
        ItemStack stack = getPickaxeStack();
        BlockState state = level.getBlockState(pos);
        Entity owner = getOwner();
        Player player = owner instanceof Player p ? p : null;

        // 1. 收割成熟作物并立即补种。
        if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
            List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), owner, stack);
            level.destroyBlock(pos, false, owner);
            // 创造模式下 Inventory.add 会直接清空放不下的部分（hasInfiniteMaterials），故不做自动拾取。
            boolean harvester = player != null && !player.hasInfiniteMaterials()
                    && Enchants.has(stack, Enchants.HARVESTER);
            for (ItemStack drop : drops) {
                if (harvester && player.getInventory().add(drop)) {
                    continue;
                }
                Block.popResource(level, pos, drop);
            }
            ItemStack seed = HoeContents.takeWithUpdate(stack, s -> isSeedFor(s, crop));
            if (seed != null) {
                level.setBlockAndUpdate(pos, crop.getStateForAge(0));
                setPickaxeStack(stack);
            }
            damageTool(1);
            return;
        }
        // 2. 耕地化。
        if (isTillable(state)) {
            level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
            damageTool(1);
            return;
        }
        if (!state.is(Blocks.FARMLAND)) {
            return;
        }
        // 3. 空耕地播种。
        BlockPos above = pos.above();
        if (level.getBlockState(above).isAir()) {
            ItemStack seed = HoeContents.takeWithUpdate(stack,
                    s -> s.getItem() instanceof BlockItem blockItem
                            && blockItem.getBlock() instanceof CropBlock);
            if (seed != null && seed.getItem() instanceof BlockItem blockItem) {
                level.setBlockAndUpdate(above, blockItem.getBlock().defaultBlockState());
                setPickaxeStack(stack);
                damageTool(1);
                return;
            }
        }
        // 4. 催熟作物。
        BlockState cropState = level.getBlockState(above);
        if (cropState.getBlock() instanceof CropBlock crop && !crop.isMaxAge(cropState)
                && fertilize(level, above, cropState, player)) {
            damageTool(1);
            return;
        }
        // 5. 精华耕地升级。
        if (upgradeFarmland(level, pos, stack)) {
            damageTool(1);
        }
    }

    private static boolean isSeedFor(ItemStack stack, CropBlock crop) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == crop;
    }

    private static boolean isTillable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM);
    }

    /** 催熟：普通作物吃骨粉，神秘农业作物吃魔法肥料。 */
    private boolean fertilize(ServerLevel level, BlockPos pos, BlockState cropState, @Nullable Player player) {
        ItemStack stack = getPickaxeStack();
        boolean mystical = "mysticalagriculture".equals(
                BuiltInRegistries.BLOCK.getKey(cropState.getBlock()).getNamespace());
        String wanted = mystical ? "mystical_fertilizer" : "bone_meal";
        ItemStack supply = HoeContents.takeWithUpdate(stack, s -> wanted.equals(
                BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
        if (supply == null) {
            return false;
        }
        setPickaxeStack(stack);
        UseOnContext context = new UseOnContext(level, player, InteractionHand.MAIN_HAND, supply,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        InteractionResult result = supply.useOn(context);
        if (!result.consumesAction()) {
            ItemStack tool = getPickaxeStack();
            HoeContents contents = tool.getOrDefault(ModComponents.HOE_CONTENTS.get(), HoeContents.EMPTY);
            tool.set(ModComponents.HOE_CONTENTS.get(), contents.insert(supply));
            setPickaxeStack(tool);
            return false;
        }
        return true;
    }

    /** 把普通耕地升级为背包中最高档精华对应的耕地。 */
    private boolean upgradeFarmland(ServerLevel level, BlockPos pos, ItemStack stack) {
        BlockState state = level.getBlockState(pos);
        int current = farmlandTier(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        for (int i = FARM_TIERS.length - 1; i >= 0; i--) {
            if (i <= current) {
                continue;
            }
            String tier = FARM_TIERS[i];
            ItemStack essence = HoeContents.takeWithUpdate(stack, s -> (tier + "_essence").equals(
                    BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
            if (essence == null) {
                continue;
            }
            setPickaxeStack(stack);
            Block farmland = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                    "mysticalagriculture", tier + "_farmland"));
            if (farmland == Blocks.AIR) {
                return false;
            }
            level.setBlockAndUpdate(pos, farmland.defaultBlockState());
            return true;
        }
        return false;
    }

    /** 普通耕地返回 -1；神秘农业精华耕地返回 0..4；非耕地返回 -2。 */
    private static int farmlandTier(ResourceLocation id) {
        if ("minecraft".equals(id.getNamespace()) && "farmland".equals(id.getPath())) {
            return -1;
        }
        if (!"mysticalagriculture".equals(id.getNamespace())) {
            return -2;
        }
        String path = id.getPath();
        for (int i = 0; i < FARM_TIERS.length; i++) {
            if ((FARM_TIERS[i] + "_farmland").equals(path)) {
                return i;
            }
        }
        return -2;
    }

    // ------------------------------------------------------------------ 实体伤害

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (level().isClientSide) {
            return;
        }
        Entity target = hit.getEntity();
        ToolKind kind = kind();
        if (kind == null || !(target instanceof LivingEntity living)) {
            return;
        }
        if (kind == ToolKind.SWORD) {
            // 飞行穿透：同目标只结算一次，不减速、不消失。
            if (!flightHit.add(target.getUUID())) {
                return;
            }
            hurtLiving(living, toolDamage * kind.directMultiplier());
            damageTool(1);
            return;
        }
        float damage = toolDamage * kind.directMultiplier();
        if (kind == ToolKind.MACE) {
            double speed = 2.0 * getDeltaMovement().length();
            double fall = Math.max(0.0, launchY - target.getY());
            damage = (float) (5.0 * speed + 1.5625 * fall);
        }
        boolean applied = hurtLiving(living, damage);
        if (applied && kind == ToolKind.MACE && level() instanceof ServerLevel server) {
            addMaceDamage(server, Math.round(damage * 10.0f));
        }
        setDeltaMovement(getDeltaMovement().scale(DIRECT_HIT_STOP));
    }

    /** 实际造成伤害才返回 true（无敌帧内命中不计入重锤耐久累计）。 */
    private boolean hurtLiving(LivingEntity target, float damage) {
        Entity owner = getOwner();
        if (owner instanceof LivingEntity living) {
            return target.hurt(damageSources().mobAttack(living), damage);
        }
        return target.hurt(damageSources().generic(), damage);
    }

    /** 重锤：每累计 10 点投掷伤害扣 1 点耐久（余数跨投掷保留在物品上）。 */
    private void addMaceDamage(ServerLevel level, int tenths) {
        ItemStack stack = getPickaxeStack();
        int stored = stack.getOrDefault(ModComponents.MACE_CHARGE.get(), 0);
        int total = stored + tenths;
        int points = total / 100;
        stack.set(ModComponents.MACE_CHARGE.get(), total % 100);
        setPickaxeStack(stack);
        if (points > 0) {
            damageTool(points);
        }
    }

    /** 重锤震波伤害计入每 10 点扣 1 耐久的累计。 */
    public void recordShockwaveDamage(float damage) {
        if (level() instanceof ServerLevel server) {
            addMaceDamage(server, Math.round(damage * 10.0f));
        }
    }

    /** 重锤命中方块：直接叠加 20% 破坏进度，不扣耐久。 */
    private void maceHit(BlockHitResult hit) {
        if (level() instanceof ServerLevel server) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = server.getBlockState(pos);
            if (!state.isAir() && state.getDestroySpeed(server, pos) >= 0.0f) {
                com.homemine.themagnetweapons.server.StackingProgress.apply(server, pos, state, 0.2f,
                        getOwner(), getPickaxeStack());
                server.levelEvent(2001, pos, Block.getId(state));
            }
        }
        stick(hit, false);
    }

    /** 对实体持有的工具扣耐久；耐久耗尽则损毁并清理占位符。 */
    public void damageTool(int amount) {
        ItemStack stack = getPickaxeStack();
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return;
        }
        int next = stack.getDamageValue() + amount;
        setPickaxeStack(withDamage(stack, next));
        if (next >= stack.getMaxDamage()) {
            breakTool();
        }
    }

    private static ItemStack withDamage(ItemStack stack, int damage) {
        ItemStack copy = stack.copy();
        copy.setDamageValue(damage);
        return copy;
    }

    private void breakTool() {
        Player player = getOwner() instanceof Player p ? p : null;
        if (player != null) {
            clearPlaceholder(player);
            level().playSound(null, player.blockPosition(), SoundEvents.ITEM_BREAK,
                    SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        discard();
    }

    private void maybeSiphon(ItemStack stack) {
        int level = Enchants.level(stack, Enchants.SIPHON);
        if (level > 0 && random.nextFloat() < 0.03f * level) {
            int damage = stack.getDamageValue();
            if (damage > 0) {
                setPickaxeStack(withDamage(stack, damage - 1));
            }
        }
    }

    // ------------------------------------------------------------------ 回收

    public void startRecall() {
        if (level().isClientSide) {
            return;
        }
        sliding = false;
        returnHit.clear();
        setStuck(false);
        setReturning(false);
        customReturning = true;
        recallTicks = 0;
        setRecallPhase(1);
        setNoGravity(true);
        noPhysics = true;
        setDeltaMovement(Vec3.ZERO);
    }

    public boolean isCustomReturning() {
        return customReturning;
    }

    private void tickCustomReturn() {
        Entity owner = getOwner();
        if (!(owner instanceof Player player) || !owner.isAlive()) {
            if (!level().isClientSide) {
                spawnAtLocation(getPickaxeStack().copy());
            }
            discard();
            return;
        }
        // 前摇：武器原地静止（客户端播放玩家挥臂 + 武器颤抖），随后才开始回飞。
        if (recallTicks < RECALL_WINDUP_TICKS) {
            recallTicks++;
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        setRecallPhase(2);
        swordReturnDamage();
        Vec3 diff = player.getBoundingBox().getCenter().subtract(position());
        double dist = diff.length();
        // 进入以玩家为中心半径 1 格的球体即立即收回。
        if (dist <= 1.0) {
            giveBack(player);
            return;
        }
        // 用剩余 tick 均分距离，保证 50 m 时前摇 + 回飞不超过 15 tick（0.75 s）。
        int remaining = Math.max(1, RECALL_TOTAL_TICKS - recallTicks);
        recallTicks++;
        double speed = Math.max(0.6, Math.min(8.0, dist / remaining));
        Vec3 motion = diff.normalize().scale(speed);
        setDeltaMovement(motion);
        move(MoverType.SELF, motion);
        if (player.getBoundingBox().getCenter().distanceTo(position()) <= 1.0) {
            giveBack(player);
        }
    }

    /** 回收阶段：剑穿过的目标再各受一次伤害。 */
    private void swordReturnDamage() {
        if (kind() != ToolKind.SWORD) {
            return;
        }
        List<Entity> targets = level().getEntities(this, getBoundingBox().inflate(0.75),
                e -> e instanceof LivingEntity);
        for (Entity entity : targets) {
            if (!(entity instanceof LivingEntity living) || living == getOwner()) {
                continue;
            }
            if (living instanceof OwnableEntity ownable && ownable.getOwner() != null) {
                continue;
            }
            if (!returnHit.add(living.getUUID())) {
                continue;
            }
            hurtLiving(living, toolDamage * kind().directMultiplier());
            damageTool(1);
        }
    }

    private void giveBack(Player player) {
        if (!level().isClientSide) {
            ItemStack tool = getPickaxeStack().copy();
            boolean restored = false;
            Inventory inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack slot = inventory.getItem(i);
                if (!(slot.getItem() instanceof ToolPlaceholderItem)) {
                    continue;
                }
                ThrownToolData data = slot.get(ModComponents.THROWN_TOOL.get());
                if (data == null || !data.entityId().equals(getUUID())) {
                    continue;
                }
                inventory.setItem(i, tool);
                restored = true;
                break;
            }
            if (!restored && !inventory.add(tool)) {
                player.drop(tool, false);
            }
            level().playSound(null, player.blockPosition(), SoundEvents.TRIDENT_RETURN,
                    SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        discard();
    }

    /**
     * 强制回收（服务端跟踪器调用）。实体没被 tick、没飞回主人身边时，
     * 直接把武器写回占位符槽，保证出手 30 s 内一定回收。
     */
    public void forceReturnToOwner() {
        if (level().isClientSide) {
            return;
        }
        Entity owner = getOwner();
        if (owner instanceof Player player && player.isAlive()) {
            giveBack(player);
            return;
        }
        if (!isRemoved()) {
            spawnAtLocation(getPickaxeStack().copy());
            discard();
        }
    }

    private void clearPlaceholder(Player player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!(slot.getItem() instanceof ToolPlaceholderItem)) {
                continue;
            }
            ThrownToolData data = slot.get(ModComponents.THROWN_TOOL.get());
            if (data != null && data.entityId().equals(getUUID())) {
                inventory.setItem(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    /** 重锤落地震波：给范围内生物附加 1 s 眩晕（缓慢 + 虚弱）。 */
    public static void applyShockwaveDebuff(LivingEntity living, @Nullable Entity owner) {
        if (living == owner || living instanceof Player) {
            return;
        }
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 4), owner);
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20, 1), owner);
    }
}
