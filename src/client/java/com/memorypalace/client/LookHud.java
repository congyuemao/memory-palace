package com.memorypalace.client;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.mc.LookPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.OptionalInt;

/**
 * 准星对准雕纹书架的某一格（或讲台）时，在准星下面显示那本书的名字。
 * 原版不会把书架里的书同步给客户端，所以对准的格子变了就问一次服务端；一直对着时每秒再问一次（书可能被换了）。
 */
final class LookHud {
	private static final int REFRESH_TICKS = 20;

	private static BlockPos lookPos;
	private static int lookSlot;
	private static String text = "";
	private static int ticksSinceQuery;

	private LookHud() {}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(LookPayloads.LookInfo.TYPE, (info, context) -> {
			if (info.pos().equals(lookPos) && info.slot() == lookSlot) {
				text = info.text();
			}
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, MemoryPalace.id("look_title"), (graphics, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (text.isEmpty() || mc.gui.screen() != null) {
				return;
			}
			int cx = graphics.guiWidth() / 2;
			int y = graphics.guiHeight() / 2 + 10;
			int w = mc.font.width(text);
			graphics.fill(cx - w / 2 - 3, y - 2, cx + w / 2 + 3, y + 10, 0x90000000);
			graphics.centeredText(mc.font, text, cx, y, 0xFFFFFFFF);
		});
	}

	static void tick(Minecraft mc) {
		BlockPos pos = null;
		int slot = 0;
		if (mc.level != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(hit.getBlockPos());
			if (state.getBlock() instanceof ChiseledBookShelfBlock shelf) {
				OptionalInt s = shelf.getHitSlot(hit, state.getValue(HorizontalDirectionalBlock.FACING));
				// 格子有没有书记在方块状态里，客户端看得到：空格子不用问
				if (s.isPresent() && state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(s.getAsInt()))) {
					pos = hit.getBlockPos();
					slot = s.getAsInt();
				}
			} else if (state.getBlock() instanceof LecternBlock && state.getValue(LecternBlock.HAS_BOOK)) {
				pos = hit.getBlockPos();
				slot = NoteLocations.LECTERN_SLOT;
			}
		}
		if (pos == null) {
			lookPos = null;
			text = "";
			return;
		}
		boolean changed = !pos.equals(lookPos) || slot != lookSlot;
		if (changed) {
			lookPos = pos;
			lookSlot = slot;
			text = "";
		}
		if (changed || ++ticksSinceQuery >= REFRESH_TICKS) {
			ticksSinceQuery = 0;
			ClientPlayNetworking.send(new LookPayloads.LookQuery(pos, slot));
		}
	}
}
