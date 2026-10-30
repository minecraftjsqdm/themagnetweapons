package com.homemine.themagnetweapons;

import com.homemine.themagnetweapons.component.Polarity;
import com.homemine.themagnetweapons.item.MeteoriteMagnetItem;
import com.homemine.themagnetweapons.tool.TierDef;
import com.homemine.themagnetweapons.tool.ToolKind;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 单一创造栏「磁性武器」：本模组全部武器、矿磁铁、矿石与 8 本附魔书。
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MagneticWeapons.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAGNETIC_WEAPONS =
            TABS.register("magnetic_weapons", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.themagnetweapons"))
                    .icon(() -> new ItemStack(iconPickaxe().get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.FOOL_GOLD_ORE_ITEM.get());
                        output.accept(ModBlocks.DEEPSLATE_FOOL_GOLD_ORE_ITEM.get());
                        output.accept(ModItems.RAW_FOOL_GOLD.get());
                        output.accept(magnet(Polarity.RED));
                        output.accept(magnet(Polarity.BLUE));
                        output.accept(ModItems.MAGNET_SMITHING_TEMPLATE.get());
                        for (TierDef tier : TierDef.values()) {
                            if (!tier.available()) {
                                continue;
                            }
                            for (ToolKind kind : ToolKind.values()) {
                                if (kind != ToolKind.MACE) {
                                    output.accept(ModItems.tool(tier, kind).get());
                                }
                            }
                            if (tier == TierDef.NETHERITE) {
                                output.accept(ModItems.MAGNETIC_MACE.get());
                            }
                        }
                        addEnchantedBooks(parameters, output);
                    })
                    .build());

    private static DeferredItem<Item> iconPickaxe() {
        TierDef[] tiers = TierDef.values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (tiers[i].available()) {
                return ModItems.tool(tiers[i], ToolKind.PICKAXE);
            }
        }
        return ModItems.tool(TierDef.IRON, ToolKind.PICKAXE);
    }

    private static ItemStack magnet(Polarity polarity) {
        return MeteoriteMagnetItem.withPolarity(new ItemStack(ModItems.METEORITE_MAGNET.get()), polarity);
    }

    private static void addEnchantedBooks(CreativeModeTab.ItemDisplayParameters parameters,
                                          CreativeModeTab.Output output) {
        List<ResourceKey<Enchantment>> keys = List.of(
                Enchants.LOYALTY, Enchants.WRIGGLER, Enchants.STABILIZER, Enchants.REACH,
                Enchants.IMPACT, Enchants.SIPHON, Enchants.PRECISION, Enchants.HARVESTER);
        HolderLookup.RegistryLookup<Enchantment> lookup = parameters.holders().lookupOrThrow(Registries.ENCHANTMENT);
        for (ResourceKey<Enchantment> key : keys) {
            lookup.get(key).ifPresent(holder -> output.accept(enchantedBook(holder)));
        }
    }

    private static ItemStack enchantedBook(Holder<Enchantment> enchantment) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(enchantment, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }

    private ModCreativeTabs() {
    }
}
