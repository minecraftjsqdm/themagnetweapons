package com.homemine.themagnetweapons.server;

import com.homemine.themagnetweapons.component.Polarity;
import com.homemine.themagnetweapons.entity.MeteoriteMagnetEntity;
import com.homemine.themagnetweapons.item.MeteoriteMagnetItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 磁极场：把「手持陨石磁铁的实体」与「身上吸着陨石磁铁的实体」当作锚点，
 * 同极反向弹射、异极互相吸引。至少一方必须是吸附态才会生效。
 */
public final class MagnetFieldManager {
    private static final ResourceLocation SLOW_ID =
            ResourceLocation.fromNamespaceAndPath("themagnetweapons", "magnet_impact_slow");

    private static final int SCAN_INTERVAL = 4;
    private static final int CHARGE_TICKS = 16;   // 0.8s 倒计时
    private static final int MOTION_TICKS = 12;
    private static final int COOLDOWN_TICKS = 40;
    private static final int SLOW_TICKS = 30;     // 1.5s

    private static final double SAME_THRESHOLD = 6.0;
    private static final double OPPOSITE_THRESHOLD = 8.0;
    private static final double MAX_IMPACT_DISTANCE = 6.0;

    private static final Map<Long, Charge> CHARGES = new HashMap<>();
    private static final Map<Long, Integer> COOLDOWNS = new HashMap<>();
    private static final List<Motion> MOTIONS = new ArrayList<>();
    private static final List<Slow> SLOWS = new ArrayList<>();
    private static int scanTimer;

    public static void tick(MinecraftServer server) {
        if (++scanTimer >= SCAN_INTERVAL) {
            scanTimer = 0;
            scan(server);
        }
        tickCharges();
        tickMotions();
        tickCooldowns();
        tickSlows();
    }

    public static void clearAll() {
        CHARGES.values().forEach(MagnetFieldManager::discardCountdown);
        CHARGES.clear();
        COOLDOWNS.clear();
        MOTIONS.clear();
        SLOWS.clear();
        scanTimer = 0;
    }

    // ------------------------------------------------------------------ 锚点扫描

    private static void scan(MinecraftServer server) {
        List<Anchor> anchors = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack stack = heldMagnet(player);
            if (!stack.isEmpty()) {
                anchors.add(new Anchor(player, MeteoriteMagnetItem.polarity(stack), false));
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof MeteoriteMagnetEntity magnet && magnet.isAttached()) {
                    Entity carrier = magnet.resolveCarrier();
                    if (carrier instanceof LivingEntity living && living.isAlive()) {
                        anchors.add(new Anchor(living, magnet.polarity(), true));
                    }
                }
            }
        }

        for (int i = 0; i < anchors.size(); i++) {
            for (int j = i + 1; j < anchors.size(); j++) {
                Anchor a = anchors.get(i);
                Anchor b = anchors.get(j);
                if (!a.attached && !b.attached) {
                    continue;
                }
                Entity ea = a.entity;
                Entity eb = b.entity;
                if (ea == eb || ea.level() != eb.level() || !ea.isAlive() || !eb.isAlive()) {
                    continue;
                }
                long key = pairKey(ea, eb);
                if (CHARGES.containsKey(key) || COOLDOWNS.containsKey(key)) {
                    continue;
                }
                boolean same = a.polarity == b.polarity;
                double threshold = same ? SAME_THRESHOLD : OPPOSITE_THRESHOLD;
                if (ea.position().distanceTo(eb.position()) > threshold) {
                    continue;
                }
                CHARGES.put(key, new Charge(ea, eb, same, spawnCountdown(ea, eb, same)));
            }
        }
    }

    private static ItemStack heldMagnet(LivingEntity entity) {
        ItemStack main = entity.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof MeteoriteMagnetItem) {
            return main;
        }
        ItemStack off = entity.getItemInHand(InteractionHand.OFF_HAND);
        return off.getItem() instanceof MeteoriteMagnetItem ? off : ItemStack.EMPTY;
    }

    private static long pairKey(Entity a, Entity b) {
        int low = Math.min(a.getId(), b.getId());
        int high = Math.max(a.getId(), b.getId());
        return ((long) low << 32) | (high & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ 倒计时

    private static void tickCharges() {
        Iterator<Map.Entry<Long, Charge>> iterator = CHARGES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Charge> entry = iterator.next();
            Charge charge = entry.getValue();
            if (!usable(charge.a) || !usable(charge.b)) {
                discardCountdown(charge);
                iterator.remove();
                continue;
            }
            charge.ticks--;
            if (charge.ticks > 0) {
                updateCountdown(charge);
                continue;
            }
            discardCountdown(charge);
            iterator.remove();
            startMotion(entry.getKey(), charge);
        }
    }

    // ------------------------------------------------------------------ 倒计时显示

    @Nullable
    private static ArmorStand spawnCountdown(Entity a, Entity b, boolean same) {
        if (!(a.level() instanceof ServerLevel level)) {
            return null;
        }
        Vec3 mid = a.position().add(b.position()).scale(0.5);
        ArmorStand stand = new ArmorStand(level, mid.x,
                Math.max(a.getY(), b.getY()) + 1.35, mid.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomNameVisible(true);
        stand.setCustomName(countdownText(same, CHARGE_TICKS));
        level.addFreshEntity(stand);
        return stand;
    }

    private static void updateCountdown(Charge charge) {
        ArmorStand stand = charge.display;
        if (stand == null || stand.isRemoved()) {
            return;
        }
        stand.setCustomName(countdownText(charge.same, Math.max(charge.ticks, 0)));
        Vec3 mid = charge.a.position().add(charge.b.position()).scale(0.5);
        stand.setPos(mid.x, Math.max(charge.a.getY(), charge.b.getY()) + 1.35, mid.z);
    }

    private static void discardCountdown(Charge charge) {
        if (charge.display != null) {
            charge.display.discard();
            charge.display = null;
        }
    }

    private static Component countdownText(boolean same, int ticks) {
        String label = same ? "弹射" : "吸引";
        String value = String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
        return Component.literal(label + " " + value)
                .withStyle(same ? ChatFormatting.RED : ChatFormatting.AQUA);
    }

    private static boolean usable(Entity entity) {
        return entity.isAlive() && !entity.isRemoved() && entity.level() != null;
    }

    private static void startMotion(long key, Charge charge) {
        // 飞行期间也占用该配对，避免扫描再起一次倒计时。
        COOLDOWNS.put(key, COOLDOWN_TICKS);
        Vec3 delta = charge.b.position().subtract(charge.a.position());
        double distance = delta.length();
        if (distance < 1.0E-3) {
            return;
        }
        Vec3 dirA = charge.same ? delta.normalize().reverse() : delta.normalize();
        double threshold = charge.same ? SAME_THRESHOLD : OPPOSITE_THRESHOLD;
        double travel = Mth.clamp(threshold - 0.5 * distance, 0.5, threshold);
        if (!charge.same) {
            double maxEach = Math.max(0.0, (distance - 0.5) * 0.5);
            travel = Math.min(travel, maxEach);
        }
        if (travel < 1.0E-3) {
            return;
        }
        double step = travel / MOTION_TICKS;
        MOTIONS.add(new Motion(key, charge.a, charge.b, charge.same, dirA, step,
                charge.a.position(), charge.b.position()));
    }

    // ------------------------------------------------------------------ 弹射 / 吸引

    private static void tickMotions() {
        Iterator<Motion> iterator = MOTIONS.iterator();
        while (iterator.hasNext()) {
            Motion motion = iterator.next();
            if (!usable(motion.a) || !usable(motion.b)) {
                finishMotion(motion);
                iterator.remove();
                continue;
            }
            stepSide(motion, true);
            stepSide(motion, false);
            if (--motion.ticks <= 0) {
                finishMotion(motion);
                iterator.remove();
                COOLDOWNS.put(motion.key, COOLDOWN_TICKS);
                if (motion.same) {
                    // 一次弹射完成后，吸附态磁铁的磁性立即耗尽。
                    detachMagnets(motion.a);
                    detachMagnets(motion.b);
                }
            }
        }
    }

    private static void stepSide(Motion motion, boolean first) {
        if (first ? motion.stoppedA : motion.stoppedB) {
            return;
        }
        Entity entity = first ? motion.a : motion.b;
        if (!entity.isAlive() || entity.isRemoved() || entity.isPassenger()) {
            stopSide(motion, first);
            return;
        }
        Vec3 dir = first ? motion.dirA : motion.dirA.reverse();
        double step = motion.step;
        if (step < 1.0E-4) {
            stopSide(motion, first);
            return;
        }
        Vec3 start = first ? motion.startA : motion.startB;

        if (entity instanceof ServerPlayer player) {
            if (player.horizontalCollision || player.verticalCollision) {
                stopSide(motion, first);
                impact(player, start.distanceTo(player.position()));
                return;
            }
            if (overlapsLiving(player)) {
                stopSide(motion, first);
                return;
            }
            player.setNoGravity(true);
            player.resetFallDistance();
            player.setDeltaMovement(dir.scale(step));
            player.hurtMarked = true;
            return;
        }

        Vec3 before = entity.position();
        entity.move(MoverType.SELF, dir.scale(step));
        double moved = entity.position().subtract(before).length();
        if (entity.horizontalCollision || entity.verticalCollision || moved < step * 0.5) {
            stopSide(motion, first);
            if (entity instanceof LivingEntity living) {
                impact(living, start.distanceTo(entity.position()));
            }
            return;
        }
        if (overlapsLiving(entity)) {
            stopSide(motion, first);
        }
    }

    private static boolean overlapsLiving(Entity entity) {
        AABB box = entity.getBoundingBox().inflate(0.02);
        return !entity.level().getEntities(entity, box, other -> other instanceof LivingEntity && other.isAlive())
                .isEmpty();
    }

    private static void stopSide(Motion motion, boolean first) {
        Entity entity = first ? motion.a : motion.b;
        if (first) {
            motion.stoppedA = true;
        } else {
            motion.stoppedB = true;
        }
        if (entity instanceof ServerPlayer player) {
            player.setNoGravity(false);
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
        }
    }

    private static void finishMotion(Motion motion) {
        releasePlayer(motion.a);
        releasePlayer(motion.b);
    }

    /** 让吸附在实体上的磁铁立即脱落为掉落物。 */
    private static void detachMagnets(Entity entity) {
        if (entity.level() == null || entity.level().isClientSide) {
            return;
        }
        AABB box = entity.getBoundingBox().inflate(3.0);
        for (MeteoriteMagnetEntity magnet : entity.level().getEntitiesOfClass(MeteoriteMagnetEntity.class, box,
                m -> m.isAttached() && m.carrierEntityId() == entity.getId())) {
            magnet.releaseMagnetism();
        }
    }

    private static void releasePlayer(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            player.setNoGravity(false);
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
        }
    }

    /** 撞方块：按移动距离造成 1~4 点伤害与 5%~30% 减速（6 格封顶）。 */
    private static void impact(LivingEntity entity, double moved) {
        double ratio = Mth.clamp(moved / MAX_IMPACT_DISTANCE, 0.0, 1.0);
        float damage = (float) Mth.clamp(1.0 + 3.0 * ratio, 1.0, 4.0);
        entity.hurt(entity.damageSources().flyIntoWall(), damage);
        double amount = Mth.clamp(0.05 + 0.25 * ratio, 0.05, 0.30);
        applySlow(entity, amount);
    }

    private static void applySlow(LivingEntity entity, double amount) {
        AttributeInstance attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(SLOW_ID);
        attribute.addTransientModifier(new AttributeModifier(SLOW_ID, -amount,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        SLOWS.add(new Slow(entity, SLOW_TICKS));
    }

    // ------------------------------------------------------------------ 冷却 / 减速偿还

    private static void tickCooldowns() {
        Iterator<Map.Entry<Long, Integer>> iterator = COOLDOWNS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Integer> entry = iterator.next();
            if (entry.getValue() <= 1) {
                iterator.remove();
            } else {
                entry.setValue(entry.getValue() - 1);
            }
        }
    }

    private static void tickSlows() {
        Iterator<Slow> iterator = SLOWS.iterator();
        while (iterator.hasNext()) {
            Slow slow = iterator.next();
            if (--slow.ticks > 0 && usable(slow.entity)) {
                continue;
            }
            AttributeInstance attribute = slow.entity.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attribute != null) {
                attribute.removeModifier(SLOW_ID);
            }
            iterator.remove();
        }
    }

    // ------------------------------------------------------------------ 数据结构

    private record Anchor(Entity entity, Polarity polarity, boolean attached) {
    }

    private static final class Charge {
        final Entity a;
        final Entity b;
        final boolean same;
        int ticks = CHARGE_TICKS;
        @Nullable
        ArmorStand display;

        Charge(Entity a, Entity b, boolean same, @Nullable ArmorStand display) {
            this.a = a;
            this.b = b;
            this.same = same;
            this.display = display;
        }
    }

    private static final class Motion {
        final long key;
        final Entity a;
        final Entity b;
        final boolean same;
        final Vec3 dirA;
        final double step;
        final Vec3 startA;
        final Vec3 startB;
        int ticks = MOTION_TICKS;
        boolean stoppedA;
        boolean stoppedB;

        Motion(long key, Entity a, Entity b, boolean same, Vec3 dirA, double step,
               Vec3 startA, Vec3 startB) {
            this.key = key;
            this.a = a;
            this.b = b;
            this.same = same;
            this.dirA = dirA;
            this.step = step;
            this.startA = startA;
            this.startB = startB;
        }
    }

    private static final class Slow {
        final LivingEntity entity;
        int ticks;

        Slow(LivingEntity entity, int ticks) {
            this.entity = entity;
            this.ticks = ticks;
        }
    }

    private MagnetFieldManager() {
    }
}
