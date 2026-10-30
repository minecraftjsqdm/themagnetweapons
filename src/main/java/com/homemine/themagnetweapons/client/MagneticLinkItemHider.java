package com.homemine.themagnetweapons.client;

import com.homemine.themagnetweapons.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 第三人称 / 其他玩家视角下隐藏磁性链接的占位符物品模型。
 *
 * <p>第一人称由 {@link MagneticLinkEffectRenderer#onRenderHand} 取消物品渲染；
 * 第三人称的手臂物品由 {@code PlayerRenderer} 内部的 {@code PlayerItemInHandLayer} 绘制，
 * 原版没有移除接口，因此这里反射取出 {@code LivingEntityRenderer#layers}，
 * 把该 layer 换成只过滤占位符的子类，其余物品照常绘制。</p>
 */
public final class MagneticLinkItemHider {
    private MagneticLinkItemHider() {
    }

    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        ItemInHandRenderer itemInHandRenderer = event.getContext().getItemInHandRenderer();
        for (var skin : event.getSkins()) {
            if (!(event.getSkin(skin) instanceof PlayerRenderer renderer)) continue;
            List<?> layers = layerList(renderer);
            if (layers == null) continue;
            boolean installed = layers.stream().anyMatch(layer -> layer instanceof HiddenHolderLayer);
            layers.removeIf(layer -> layer instanceof PlayerItemInHandLayer && !(layer instanceof HiddenHolderLayer));
            if (!installed) {
                renderer.addLayer(new HiddenHolderLayer<>(renderer, itemInHandRenderer));
            }
        }
    }

    private static List<?> layerList(LivingEntityRenderer<?, ?> renderer) {
        try {
            Field field = LivingEntityRenderer.class.getDeclaredField("layers");
            field.setAccessible(true);
            return (List<?>) field.get(renderer);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static final class HiddenHolderLayer<T extends Player, M extends EntityModel<T> & ArmedModel & HeadedModel>
            extends PlayerItemInHandLayer<T, M> {
        private HiddenHolderLayer(RenderLayerParent<T, M> parent, ItemInHandRenderer itemInHandRenderer) {
            super(parent, itemInHandRenderer);
        }

        @Override
        protected void renderArmWithItem(LivingEntity entity, ItemStack stack, ItemDisplayContext displayContext,
                HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
            if (stack.is(ModItems.TOOL_PLACEHOLDER.get())) return;
            super.renderArmWithItem(entity, stack, displayContext, arm, poseStack, buffer, packedLight);
        }
    }
}
