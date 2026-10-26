package com.memorypalace.client;

import com.memorypalace.MemoryPalace;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * 找笔记后的导航：在目标书架上冒一道光柱（END_ROD 粒子），屏幕顶部显示方向箭头和距离。
 * 走到书架跟前（3 格内）就算到达，自动结束。
 */
public final class Navigator {
	private static final double ARRIVE_DISTANCE = 3.0;
	private static final double PARTICLE_RANGE = 64.0;
	private static final long TIMEOUT_MS = 10 * 60_000L;
	// 八个方向：正前方、右前、右、右后、后、左后、左、左前
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	private static String dimension;
	private static BlockPos target;
	private static String title;
	private static long startedAt;
	private static int tickCounter;

	private Navigator() {}

	public static boolean active() {
		return target != null;
	}

	public static void start(String dim, BlockPos pos, String name) {
		dimension = dim;
		target = pos;
		title = name;
		startedAt = System.currentTimeMillis();
		tell(Component.translatable("message.memorypalace.nav_start", name));
	}

	public static void stop() {
		target = null;
	}

	static void register() {
		HudElementRegistry.addLast(MemoryPalace.id("navigator"), (graphics, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (target == null || mc.player == null) {
				return;
			}
			Component line;
			if (!currentDimension(mc).equals(dimension)) {
				line = Component.translatable("screen.memorypalace.nav_other_dim", title, dimensionName(dimension));
			} else {
				Vec3 feet = mc.player.position();
				Vec3 goal = Vec3.atCenterOf(target);
				// 水平距离 + 高度差：书架在楼上或地下室时，光看平面箭头不知道该往上还是往下
				int horizontal = (int) Math.round(Math.hypot(goal.x - feet.x, goal.z - feet.z));
				int dy = target.getY() - (int) Math.floor(feet.y);
				Component vertical = dy >= 2 ? Component.translatable("screen.memorypalace.nav_up", dy)
						: dy <= -2 ? Component.translatable("screen.memorypalace.nav_down", -dy)
						: Component.empty();
				line = Component.translatable("screen.memorypalace.nav_hud", arrow(mc, goal), title, horizontal).append(vertical);
			}
			int cx = graphics.guiWidth() / 2;
			int w = mc.font.width(line);
			graphics.fill(cx - w / 2 - 4, 2, cx + w / 2 + 4, 15, 0x90000000);
			graphics.centeredText(mc.font, line, cx, 5, 0xFFFFE080);
		});
	}

	static void tick(Minecraft mc) {
		if (target == null) {
			return;
		}
		if (mc.player == null || mc.level == null || System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
			stop();
			return;
		}
		if (!currentDimension(mc).equals(dimension)) {
			return;
		}
		Vec3 goal = Vec3.atCenterOf(target);
		double distance = mc.player.position().distanceTo(goal);
		if (distance <= ARRIVE_DISTANCE) {
			tell(Component.translatable("message.memorypalace.nav_arrived", title));
			stop();
			return;
		}
		// 光柱：从书架往上冒的一串粒子，远处也看得到方向
		if (distance <= PARTICLE_RANGE && ++tickCounter % 3 == 0) {
			for (int i = 0; i < 4; i++) {
				mc.level.addParticle(ParticleTypes.END_ROD, goal.x, goal.y + 0.6 + i * 0.8, goal.z, 0, 0.04, 0);
			}
		}
	}

	/** 目标相对玩家朝向的箭头。原版的 yaw：顺时针（向右转）变大，0 = 朝南。 */
	private static String arrow(Minecraft mc, Vec3 goal) {
		Vec3 p = mc.player.position();
		double targetYaw = Math.toDegrees(Math.atan2(-(goal.x - p.x), goal.z - p.z));
		double diff = ((targetYaw - mc.player.getYRot()) % 360 + 540) % 360 - 180; // -180..180，正数在右边
		int index = (int) Math.floorMod(Math.round(diff / 45.0), 8);
		return ARROWS[index];
	}

	private static String currentDimension(Minecraft mc) {
		return mc.level.dimension().identifier().toString();
	}

	static Component dimensionName(String dim) {
		return switch (dim) {
			case "minecraft:overworld" -> Component.translatable("command.memorypalace.dim.overworld");
			case "minecraft:the_nether" -> Component.translatable("command.memorypalace.dim.nether");
			case "minecraft:the_end" -> Component.translatable("command.memorypalace.dim.end");
			default -> Component.literal(dim);
		};
	}

	private static void tell(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(message);
		}
	}
}
