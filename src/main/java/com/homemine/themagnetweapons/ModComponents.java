package com.homemine.themagnetweapons;

import com.homemine.themagnetweapons.component.HoeContents;
import com.homemine.themagnetweapons.component.Polarity;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MagneticWeapons.MODID);

    /**
     * 稳定模式开关（稳定器附魔）：
     * true 时矿镐投出后不再累加方块破坏进度。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> STABLE =
            COMPONENTS.register("stable", () -> DataComponentType.<Boolean>builder()
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL)
                    .build());

    /** 占位符记录：对应的投掷物 UUID 与被投出的工具本体。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ThrownToolData>> THROWN_TOOL =
            COMPONENTS.register("thrown_tool", () -> DataComponentType.<ThrownToolData>builder()
                    .persistent(ThrownToolData.CODEC)
                    .networkSynchronized(ThrownToolData.STREAM_CODEC)
                    .build());

    /** 重锤投掷伤害余数（单位为 0.1 点伤害），跨投掷累计，每满 100（即 10 点伤害）扣 1 耐久。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MACE_CHARGE =
            COMPONENTS.register("mace_charge", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /** 磁性锄内置农资容器。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<HoeContents>> HOE_CONTENTS =
            COMPONENTS.register("hoe_contents", () -> DataComponentType.<HoeContents>builder()
                    .persistent(HoeContents.CODEC)
                    .networkSynchronized(HoeContents.STREAM_CODEC)
                    .build());

    /** 陨石磁铁的极性（红 / 蓝），默认红。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Polarity>> POLARITY =
            COMPONENTS.register("polarity", () -> DataComponentType.<Polarity>builder()
                    .persistent(Polarity.CODEC)
                    .networkSynchronized(Polarity.STREAM_CODEC)
                    .build());

    private ModComponents() {
    }
}
