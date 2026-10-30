package com.homemine.themagnetweapons;

import com.homemine.themagnetweapons.client.ZoneOverlayRenderer;
import com.homemine.themagnetweapons.net.DashTogglePayload;
import com.homemine.themagnetweapons.net.ToggleStablePayload;
import com.homemine.themagnetweapons.net.ZoneSyncPayload;
import com.homemine.themagnetweapons.server.DashController;
import com.homemine.themagnetweapons.server.StableModeHandler;
import com.homemine.themagnetweapons.world.ModFeatures;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(MagneticWeapons.MODID)
public final class MagneticWeapons {
    public static final String MODID = "themagnetweapons";
    /** 稳定器快捷键的实际键位名，由客户端在初始化时写入，用于 tooltip 显示。 */
    public static volatile Supplier<Component> stableKeyHint = () -> Component.literal("F7");

    public MagneticWeapons(IEventBus modEventBus) {
        ModItems.ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModComponents.COMPONENTS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModFeatures.FEATURES.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        modEventBus.addListener(MagneticWeapons::registerPayloads);
        NeoForge.EVENT_BUS.register(MagneticWeaponsEvents.class);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ToggleStablePayload.TYPE, ToggleStablePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        StableModeHandler.toggle(player);
                    }
                });
        registrar.playToServer(DashTogglePayload.TYPE, DashTogglePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        DashController.toggle(player);
                    }
                });
        registrar.playToClient(ZoneSyncPayload.TYPE, ZoneSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ZoneOverlayRenderer.accept(payload)));
    }
}
