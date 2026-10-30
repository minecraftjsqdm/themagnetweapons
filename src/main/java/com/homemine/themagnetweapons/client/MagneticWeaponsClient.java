package com.homemine.themagnetweapons.client;

import com.homemine.themagnetweapons.MagneticWeapons;
import com.homemine.themagnetweapons.ModItems;
import com.homemine.themagnetweapons.component.Polarity;
import com.homemine.themagnetweapons.item.MeteoriteMagnetItem;
import com.homemine.themagnetweapons.net.ToggleStablePayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** 客户端：注册稳定器快捷键与投掷工具渲染器。 */
@Mod(value = MagneticWeapons.MODID, dist = Dist.CLIENT)
public final class MagneticWeaponsClient {
    public static final KeyMapping TOGGLE_STABLE = new KeyMapping(
            "key.themagnetweapons.toggle_stable",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            "key.categories.themagnetweapons");

    public MagneticWeaponsClient(ModContainer container) {
        IEventBus modBus = container.getEventBus();
        modBus.addListener(this::registerKeyMappings);
        modBus.addListener(this::registerRenderers);
        modBus.addListener(MagneticLinkItemHider::onAddLayers);
        modBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(ZoneOverlayRenderer::render);
        NeoForge.EVENT_BUS.addListener(MagneticLinkEffectRenderer::onRenderHand);
        NeoForge.EVENT_BUS.addListener(MagneticLinkEffectRenderer::onRenderPlayer);
        NeoForge.EVENT_BUS.addListener(this::loggingOut);
        NeoForge.EVENT_BUS.register(ClientDashInput.class);
        MagneticWeapons.stableKeyHint = TOGGLE_STABLE::getTranslatedKeyMessage;
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_STABLE);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(com.homemine.themagnetweapons.ModEntities.THROWN_MAGNETIC_TOOL.get(),
                FlatSpinThrownPickaxeRenderer::new);
        event.registerEntityRenderer(com.homemine.themagnetweapons.ModEntities.METEORITE_MAGNET.get(),
                ThrownItemRenderer::new);
    }

    /** 陨石磁铁按极性组件切换红 / 蓝两个模型。 */
    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(ModItems.METEORITE_MAGNET.get(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MagneticWeapons.MODID, "polarity"),
                    (stack, level, entity, seed) -> MeteoriteMagnetItem.polarity(stack) == Polarity.BLUE ? 1.0f : 0.0f);
        });
    }

    private void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ZoneOverlayRenderer.clear();
        RecallAnimation.clear();
    }

    private void clientTick(ClientTickEvent.Post event) {
        RecallAnimation.tick();
        while (TOGGLE_STABLE.consumeClick()) {
            PacketDistributor.sendToServer(ToggleStablePayload.INSTANCE);
        }
    }
}
