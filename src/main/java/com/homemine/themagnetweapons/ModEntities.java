package com.homemine.themagnetweapons;

import com.homemine.themagnetweapons.entity.MeteoriteMagnetEntity;
import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MagneticWeapons.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<ThrownMagneticToolEntity>> THROWN_MAGNETIC_TOOL =
            ENTITIES.register("thrown_magnetic_tool", () -> EntityType.Builder
                    .<ThrownMagneticToolEntity>of(ThrownMagneticToolEntity::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f)
                    .clientTrackingRange(8)
                    .updateInterval(10)
                    .build("thrown_magnetic_tool"));

    public static final DeferredHolder<EntityType<?>, EntityType<MeteoriteMagnetEntity>> METEORITE_MAGNET =
            ENTITIES.register("meteorite_magnet", () -> EntityType.Builder
                    .<MeteoriteMagnetEntity>of(MeteoriteMagnetEntity::new, MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .clientTrackingRange(10)
                    .updateInterval(5)
                    .build("meteorite_magnet"));

    private ModEntities() {
    }
}
