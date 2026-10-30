package com.homemine.themagnetweapons;

import com.homemine.themagnetweapons.item.MagneticAxeToolItem;
import com.homemine.themagnetweapons.item.MagneticHoeToolItem;
import com.homemine.themagnetweapons.item.MagneticMaceToolItem;
import com.homemine.themagnetweapons.item.MagneticPickaxeToolItem;
import com.homemine.themagnetweapons.item.MagneticShovelToolItem;
import com.homemine.themagnetweapons.item.MagneticSwordToolItem;
import com.homemine.themagnetweapons.item.MeteoriteMagnetItem;
import com.homemine.themagnetweapons.item.ToolPlaceholderItem;
import com.homemine.themagnetweapons.tool.TierDef;
import com.homemine.themagnetweapons.tool.ToolKind;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 7 档材质 × 5 类工具 + 1 把单档重锤 = 36 个物品。
 * 物品 id 统一为 {@code <tier>_magnetic_<kind>}（材质 id 用 golden 而非 gold）。
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MagneticWeapons.MODID);

    private static final Map<TierDef, Map<ToolKind, DeferredItem<Item>>> TOOLS = new EnumMap<>(TierDef.class);

    /** 工具 → 占位符的 custom_model_data 序号，与 tool_placeholder.json 的 overrides 一一对应。 */
    private static final Map<ResourceLocation, Integer> PLACEHOLDER_MODELS = new HashMap<>();

    static {
        int index = 0;
        for (TierDef tier : TierDef.values()) {
            Map<ToolKind, DeferredItem<Item>> byKind = new EnumMap<>(ToolKind.class);
            for (ToolKind kind : ToolKind.values()) {
                if (kind == ToolKind.MACE) {
                    continue;
                }
                String id = tier.id() + "_magnetic_" + kind.id();
                byKind.put(kind, ITEMS.registerItem(id, properties -> create(tier, kind, properties), properties(tier)));
                PLACEHOLDER_MODELS.put(ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, id), ++index);
            }
            TOOLS.put(tier, byKind);
        }
        PLACEHOLDER_MODELS.put(ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "magnetic_mace"), index + 1);
    }

    public static final DeferredItem<Item> MAGNETIC_MACE =
            ITEMS.registerItem("magnetic_mace", MagneticMaceToolItem::new, new Item.Properties().durability(500));

    /** 投掷后的占位符，不可堆叠、不可掉落展示。 */
    public static final DeferredItem<Item> TOOL_PLACEHOLDER =
            ITEMS.registerItem("tool_placeholder", ToolPlaceholderItem::new, new Item.Properties());

    /** 粗愚人金：16 个一组。 */
    public static final DeferredItem<Item> RAW_FOOL_GOLD =
            ITEMS.registerSimpleItem("raw_fool_gold", new Item.Properties().stacksTo(16));

    /** 陨石磁铁：16 个一组，可投掷、可切换极性。 */
    public static final DeferredItem<Item> METEORITE_MAGNET =
            ITEMS.registerItem("meteorite_magnet", MeteoriteMagnetItem::new,
                    new Item.Properties().stacksTo(16));

    /** 磁性升级模板：在锻造台把原版工具升级为磁性工具。 */
    public static final DeferredItem<Item> MAGNET_SMITHING_TEMPLATE =
            ITEMS.registerSimpleItem("magnet_smithing_template", new Item.Properties());

    private static Item.Properties properties(TierDef tier) {
        Item.Properties properties = new Item.Properties().durability(tier.tier().getUses());
        return tier.fireResistant() ? properties.fireResistant() : properties;
    }

    private static Item create(TierDef tier, ToolKind kind, Item.Properties properties) {
        return switch (kind) {
            case PICKAXE -> new MagneticPickaxeToolItem(tier, properties);
            case AXE -> new MagneticAxeToolItem(tier, properties);
            case SWORD -> new MagneticSwordToolItem(tier, properties);
            case SHOVEL -> new MagneticShovelToolItem(tier, properties);
            case HOE -> new MagneticHoeToolItem(tier, properties);
            case MACE -> throw new IllegalArgumentException("重锤单独注册");
        };
    }

    public static DeferredItem<Item> tool(TierDef tier, ToolKind kind) {
        if (kind == ToolKind.MACE) {
            return MAGNETIC_MACE;
        }
        return TOOLS.get(tier).get(kind);
    }

    /** 投出的工具在占位符上应使用的模型序号；未知物品返回 0（走默认贴图）。 */
    public static int placeholderModelIndex(ItemStack tool) {
        return PLACEHOLDER_MODELS.getOrDefault(BuiltInRegistries.ITEM.getKey(tool.getItem()), 0);
    }

    /** 三把高阶磁性镐的便捷访问。 */
    public static final DeferredItem<Item> END_METEORITE_MAGNETIC_PICKAXE =
            tool(TierDef.END_METEORITE, ToolKind.PICKAXE);
    public static final DeferredItem<Item> CHANGE_JADE_MAGNETIC_PICKAXE =
            tool(TierDef.CHANGE_JADE, ToolKind.PICKAXE);
    public static final DeferredItem<Item> FINAL_CRYSTAL_MAGNETIC_PICKAXE =
            tool(TierDef.FINAL_CRYSTAL, ToolKind.PICKAXE);

    private ModItems() {
    }
}
