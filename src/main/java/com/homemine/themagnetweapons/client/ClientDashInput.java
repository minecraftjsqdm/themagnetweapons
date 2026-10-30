package com.homemine.themagnetweapons.client;

import com.homemine.themagnetweapons.item.ToolPlaceholderItem;
import com.homemine.themagnetweapons.net.DashTogglePayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 客户端左键（对空气或对方块）手持占位符时，请求服务端切换冲刺 / 滑模。 */
public final class ClientDashInput {
    @SubscribeEvent(receiveCanceled = true)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity().level().isClientSide
                && event.getItemStack().getItem() instanceof ToolPlaceholderItem) {
            PacketDistributor.sendToServer(DashTogglePayload.INSTANCE);
        }
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event.getItemStack().getItem() instanceof ToolPlaceholderItem) {
            PacketDistributor.sendToServer(DashTogglePayload.INSTANCE);
        }
    }

    private ClientDashInput() {
    }
}
