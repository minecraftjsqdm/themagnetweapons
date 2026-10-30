package com.homemine.themagnetweapons.entity;

import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.ModEntities;
import com.homemine.themagnetweapons.ModItems;
import com.homemine.themagnetweapons.component.Polarity;
import com.homemine.themagnetweapons.item.MeteoriteMagnetItem;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 投出的陨石磁铁：飞行 → 落地 / 直接命中后吸附到附近实体。
 * 吸附后由 {@code MagnetFieldManager} 按极性驱动。
 */
public class MeteoriteMagnetEntity extends Projectile implements ItemSupplier {
    public static final byte STATE_FLYING = 0;
    public static final byte STATE_SETTLED = 1;
    public static final byte STATE_ATTACHED = 2;

    private static final double GRAVITY = 0.035;
    private static final double DRAG = 0.99;
    /** 落地吸附扫描半径：±2 格 = 5×5×5。 */
    private static final double ATTACH_SCAN = 2.0;
    private static final double OCCUPIED_SCAN = 2.5;
    private static final int RECHECK_INTERVAL = 20;
    /** 吸附后的磁性最多维持 20s，到时自然消失。 */
    private static final int ATTACH_LIFE = 400;

    private static final EntityDataAccessor<Byte> DATA_STATE =
            SynchedEntityData.defineId(MeteoriteMagnetEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_POLARITY =
            SynchedEntityData.defineId(MeteoriteMagnetEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_CARRIER =
            SynchedEntityData.defineId(MeteoriteMagnetEntity.class, EntityDataSerializers.INT);

    @Nullable
    private UUID carrierUuid;
    private Vec3 carrierOffset = new Vec3(0.0, 0.5, 0.0);
    private int recheckTimer;
    private int attachedTicks;

    public MeteoriteMagnetEntity(EntityType<? extends MeteoriteMagnetEntity> type, Level level) {
        super(type, level);
    }

    public static MeteoriteMagnetEntity create(Level level, Player owner, Polarity polarity) {
        MeteoriteMagnetEntity magnet = new MeteoriteMagnetEntity(ModEntities.METEORITE_MAGNET.get(), level);
        magnet.setOwner(owner);
        magnet.setPos(owner.getX(), owner.getEyeY() - 0.15, owner.getZ());
        magnet.setPolarity(polarity);
        return magnet;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STATE, STATE_FLYING);
        builder.define(DATA_POLARITY, (byte) Polarity.RED.ordinal());
        builder.define(DATA_CARRIER, -1);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        Entity owner = getOwner();
        return new ClientboundAddEntityPacket(this, serverEntity, owner == null ? 0 : owner.getId());
    }

    @Override
    public ItemStack getItem() {
        ItemStack stack = new ItemStack(ModItems.METEORITE_MAGNET.get());
        stack.set(ModComponents.POLARITY.get(), polarity());
        return stack;
    }

    public byte state() {
        return entityData.get(DATA_STATE);
    }

    private void setState(byte state) {
        entityData.set(DATA_STATE, state);
    }

    public Polarity polarity() {
        int index = entityData.get(DATA_POLARITY) & 0xFF;
        Polarity[] values = Polarity.values();
        return index >= 0 && index < values.length ? values[index] : Polarity.RED;
    }

    public void setPolarity(Polarity polarity) {
        entityData.set(DATA_POLARITY, (byte) polarity.ordinal());
    }

    public boolean isAttached() {
        return state() == STATE_ATTACHED;
    }

    public boolean isSettled() {
        return state() == STATE_SETTLED;
    }

    /** 吸附到的载体实体 id；未吸附返回 -1。 */
    public int carrierEntityId() {
        return entityData.get(DATA_CARRIER);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        switch (state()) {
            case STATE_ATTACHED -> tickAttached();
            case STATE_SETTLED -> tickSettled();
            default -> tickFlying();
        }
    }

    private void tickFlying() {
        Vec3 motion = getDeltaMovement();
        if (!level().isClientSide) {
            LivingEntity hit = findEntityHit(motion);
            if (hit != null) {
                attachTo(hit, true);
                return;
            }
        }
        move(MoverType.SELF, motion);
        if (this.horizontalCollision || this.verticalCollision) {
            if (!level().isClientSide) {
                settle();
            }
            return;
        }
        if (getY() < level().getMinBuildHeight() - 8) {
            if (!level().isClientSide) {
                discard();
            }
            return;
        }
        setDeltaMovement(motion.x * DRAG, motion.y - GRAVITY, motion.z * DRAG);
        double horizontal = motion.horizontalDistance();
        if (motion.lengthSqr() > 1.0E-4) {
            setYRot((float) (Mth.atan2(motion.x, motion.z) * (180.0 / Math.PI)));
            setXRot((float) (Mth.atan2(motion.y, horizontal) * (180.0 / Math.PI)));
        }
    }

    private void tickAttached() {
        Entity carrier = resolveCarrier();
        if (carrier == null || !carrier.isAlive()) {
            if (!level().isClientSide) {
                detachAndDrop();
            }
            return;
        }
        if (!level().isClientSide && ++attachedTicks >= ATTACH_LIFE) {
            detachAndDrop();
            return;
        }
        setNoGravity(true);
        setPos(carrier.getX() + carrierOffset.x,
                carrier.getY() + carrierOffset.y,
                carrier.getZ() + carrierOffset.z);
        setDeltaMovement(Vec3.ZERO);
    }

    private void tickSettled() {
        setDeltaMovement(Vec3.ZERO);
        if (level().isClientSide) {
            return;
        }
        if (--recheckTimer > 0) {
            return;
        }
        recheckTimer = RECHECK_INTERVAL;
        LivingEntity target = findAttachmentTarget();
        if (target != null) {
            attachTo(target, false);
        }
    }

    private void settle() {
        setState(STATE_SETTLED);
        setDeltaMovement(Vec3.ZERO);
        setNoGravity(true);
        recheckTimer = RECHECK_INTERVAL;
        level().playSound(null, blockPosition(), SoundEvents.LODESTONE_COMPASS_LOCK,
                SoundSource.NEUTRAL, 0.7f, 1.5f);
        if (level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    getX(), getY() + 0.15, getZ(), 6, 0.2, 0.2, 0.2, 0.02);
        }
        LivingEntity target = findAttachmentTarget();
        if (target != null) {
            attachTo(target, false);
        }
    }

    /** 吸附到目标实体。direct 表示直接命中（允许目标手持磁铁）。 */
    public void attachTo(LivingEntity target, boolean direct) {
        if (level().isClientSide || !target.isAlive()) {
            return;
        }
        if (!direct && holdsMagnet(target)) {
            return;
        }
        if (hasAttachedMagnet(target)) {
            return;
        }
        carrierUuid = target.getUUID();
        entityData.set(DATA_CARRIER, target.getId());
        Vec3 offset = position().subtract(target.position());
        double length = offset.length();
        if (length < 1.0E-3) {
            offset = new Vec3(0.0, Math.max(0.3, target.getBbHeight() * 0.5), 0.0);
        } else {
            offset = offset.normalize().scale(Mth.clamp(length, 0.45, 1.0));
        }
        carrierOffset = offset;
        attachedTicks = 0;
        setState(STATE_ATTACHED);
        setDeltaMovement(Vec3.ZERO);
        setNoGravity(true);
        level().playSound(null, blockPosition(), SoundEvents.LODESTONE_COMPASS_LOCK,
                SoundSource.NEUTRAL, 0.9f, 1.4f);
        if (level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    getX(), getY() + 0.15, getZ(), 8, 0.25, 0.25, 0.25, 0.03);
        }
    }

    private void detachAndDrop() {
        if (level().isClientSide) {
            return;
        }
        spawnAtLocation(getItem().copy());
        discard();
    }

    /** 磁性耗尽：立即脱落为掉落物。 */
    public void releaseMagnetism() {
        if (level().isClientSide) {
            return;
        }
        detachAndDrop();
    }

    @Nullable
    public Entity resolveCarrier() {
        if (level().isClientSide) {
            int id = entityData.get(DATA_CARRIER);
            return id < 0 ? null : level().getEntity(id);
        }
        if (carrierUuid == null) {
            return null;
        }
        return level() instanceof ServerLevel server ? server.getEntity(carrierUuid) : null;
    }

    // ------------------------------------------------------------------ 扫描

    @Nullable
    private LivingEntity findEntityHit(Vec3 motion) {
        AABB box = getBoundingBox().expandTowards(motion).inflate(0.35);
        return nearestLiving(level().getEntitiesOfClass(LivingEntity.class, box), Double.MAX_VALUE);
    }

    @Nullable
    private LivingEntity findAttachmentTarget() {
        AABB box = getBoundingBox().inflate(ATTACH_SCAN);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity candidate : level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (candidate == getOwner() || !candidate.isAlive()) {
                continue;
            }
            if (holdsMagnet(candidate) || hasAttachedMagnet(candidate)) {
                continue;
            }
            double dist = candidate.distanceToSqr(this);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    @Nullable
    private LivingEntity nearestLiving(List<LivingEntity> candidates, double limit) {
        LivingEntity best = null;
        double bestDist = limit;
        for (LivingEntity candidate : candidates) {
            if (candidate == getOwner() || !candidate.isAlive()) {
                continue;
            }
            double dist = candidate.distanceToSqr(this);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    public static boolean holdsMagnet(LivingEntity entity) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (entity.getItemInHand(hand).getItem() instanceof MeteoriteMagnetItem) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAttachedMagnet(Entity target) {
        AABB box = target.getBoundingBox().inflate(OCCUPIED_SCAN);
        return !level().getEntitiesOfClass(MeteoriteMagnetEntity.class, box,
                magnet -> magnet != this && magnet.carrierEntityId() == target.getId()).isEmpty();
    }

    // ------------------------------------------------------------------ 存档

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("State", state());
        tag.putByte("Polarity", (byte) polarity().ordinal());
        tag.putInt("AttachedTicks", attachedTicks);
        if (carrierUuid != null) {
            tag.putUUID("Carrier", carrierUuid);
        }
        tag.putDouble("OffX", carrierOffset.x);
        tag.putDouble("OffY", carrierOffset.y);
        tag.putDouble("OffZ", carrierOffset.z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(DATA_STATE, tag.getByte("State"));
        entityData.set(DATA_POLARITY, tag.getByte("Polarity"));
        attachedTicks = tag.getInt("AttachedTicks");
        if (tag.hasUUID("Carrier")) {
            carrierUuid = tag.getUUID("Carrier");
        }
        carrierOffset = new Vec3(tag.getDouble("OffX"), tag.getDouble("OffY"), tag.getDouble("OffZ"));
        if (state() == STATE_ATTACHED) {
            Entity carrier = resolveCarrier();
            entityData.set(DATA_CARRIER, carrier == null ? -1 : carrier.getId());
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }
}
