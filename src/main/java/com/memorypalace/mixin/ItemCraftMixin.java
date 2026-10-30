package com.memorypalace.mixin;

import com.memorypalace.mc.NoteItemUse;
import com.memorypalace.mc.NoteItems;
import com.memorypalace.mc.PalaceInteractions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 从合成结果栏取出物品时原版会调 Item.onCraftedBy（拿在手上、在工作台里按 Shift 取出都会）：
 * 取出的是抄本就当场抄好，手里这本直接变成新笔记的书（见 NoteItemUse.onCrafted）。
 */
@Mixin(Item.class)
public abstract class ItemCraftMixin {
	@Inject(method = "onCraftedBy(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
	private void memorypalace$copyOnCraft(ItemStack stack, Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer serverPlayer) {
			NoteItemUse.onCrafted(serverPlayer, stack);
		} else if (player.level().isClientSide()) {
			// 客户端：记下"玩家刚亲手合成了抄这一篇的抄本"，服务端接着发来的加密抄写请求才认
			NoteItems.copyOf(stack).ifPresent(PalaceInteractions::markLocalCraft);
		}
	}
}
