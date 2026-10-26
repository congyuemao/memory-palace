package com.memorypalace.client;

import com.memorypalace.mc.ToolPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 找笔记：输入关键词（标题、文件夹、正文；加密笔记只搜文件名），列出结果和它们所在的位置。
 * 点结果：有位置就开始导航（光柱 + 方向箭头）；没放到书架上过就直接打开。右边可以"打开"，创造模式下还可以"传送"。
 */
public class SearchScreen extends Screen {
	private static final long DEBOUNCE_MS = 150;
	private static final int ROW = 22;

	private static int requestCounter;
	private static SearchScreen open;

	private EditBox input;
	private String query = "";
	private List<ToolPayloads.Entry> results = List.of();
	private boolean searched;
	private boolean pending;
	private long lastEdit;
	private int latestRequest;

	public SearchScreen() {
		super(Component.translatable("screen.memorypalace.search"));
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ToolPayloads.Results.TYPE, (payload, context) -> {
			if (open != null && payload.requestId() == open.latestRequest) {
				open.results = payload.entries();
				open.searched = true;
				open.rebuildWidgets();
			}
		});
	}

	@Override
	protected void init() {
		open = this;
		int w = listWidth();
		int left = (this.width - w) / 2;
		input = new EditBox(this.font, left, 26, w, 20, this.title);
		input.setMaxLength(200);
		input.setHint(Component.translatable("screen.memorypalace.search_hint"));
		input.setValue(query);
		input.setResponder(value -> {
			query = value;
			pending = true;
			lastEdit = System.currentTimeMillis();
		});
		addRenderableWidget(input);
		setInitialFocus(input);

		if (Navigator.active()) {
			addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.nav_stop"), b -> {
				Navigator.stop();
				rebuildWidgets();
			}).bounds(left + w - 80, 4, 80, 18).build());
		}

		boolean creative = this.minecraft.player != null && this.minecraft.player.isCreative();
		int actionW = 44;
		int actions = creative ? 2 : 1;
		int mainW = w - actions * (actionW + 4);
		int y = 54;
		int maxRows = Math.max(1, (this.height - y - 8) / ROW);
		for (int i = 0; i < Math.min(maxRows, results.size()); i++) {
			ToolPayloads.Entry e = results.get(i);
			Button main = addRenderableWidget(Button.builder(rowLabel(e), b -> choose(e))
					.bounds(left, y, mainW, 20).build());
			main.setTooltip(Tooltip.create(tooltip(e)));
			int x = left + mainW + 4;
			addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.search_open"), b -> openNote(e))
					.bounds(x, y, actionW, 20).build());
			if (creative) {
				Button tp = addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.search_tp"), b -> teleport(e))
						.bounds(x + actionW + 4, y, actionW, 20).build());
				tp.active = e.located() && !e.teleport().isEmpty();
			}
			y += ROW;
		}
	}

	private int listWidth() {
		return Math.min(this.width - 20, 420);
	}

	/** 一行：标题（文件夹）· 位置（或"尚未放到书架上"）。带上文件夹，同名笔记才分得清。 */
	private Component rowLabel(ToolPayloads.Entry e) {
		Component where = e.located()
				? Component.translatable("screen.memorypalace.search_where", Navigator.dimensionName(e.dimension()),
						e.pos().getX(), e.pos().getY(), e.pos().getZ(), slotName(e.slot()))
				: Component.translatable("screen.memorypalace.search_unplaced");
		String path = e.path();
		int slash = path.lastIndexOf('/');
		// 加密笔记的标题本来就是"【加密】文件夹/文件名"，不用再加
		String folder = e.encrypted() || slash < 0 ? "" : "（" + path.substring(0, slash) + "）";
		return Component.literal(e.title() + folder + "  ·  ").append(where);
	}

	private static Component slotName(int slot) {
		return slot < 0 ? Component.translatable("command.memorypalace.where_lectern")
				: Component.translatable("command.memorypalace.where_slot", slot + 1);
	}

	/** 鼠标悬停：完整路径，加上正文里命中的那一段。 */
	private static Component tooltip(ToolPayloads.Entry e) {
		Component path = Component.literal(e.path());
		return e.snippet().isEmpty() ? path : path.copy().append("\n").append(Component.literal(e.snippet()));
	}

	/** 点一行：有位置就导航过去，没有就直接打开。 */
	private void choose(ToolPayloads.Entry e) {
		if (e.located()) {
			Navigator.start(e.dimension(), e.pos(), e.title());
			onClose();
		} else {
			openNote(e);
		}
	}

	private void openNote(ToolPayloads.Entry e) {
		ClientPlayNetworking.send(new ToolPayloads.OpenById(e.id()));
		onClose();
	}

	private void teleport(ToolPayloads.Entry e) {
		// 命令以 "/" 开头，sendCommand 不要这个斜杠；没有权限时服务端会拒绝
		Minecraft mc = Minecraft.getInstance();
		mc.player.connection.sendCommand(e.teleport().substring(1));
		onClose();
	}

	@Override
	public void tick() {
		super.tick();
		// 停止打字一小会儿再搜，免得每敲一个字都发一次
		if (pending && System.currentTimeMillis() - lastEdit >= DEBOUNCE_MS) {
			pending = false;
			if (query.isBlank()) {
				results = List.of();
				searched = false;
				rebuildWidgets();
			} else {
				latestRequest = ++requestCounter;
				ClientPlayNetworking.send(new ToolPayloads.Search(latestRequest, query));
			}
		}
	}

	@Override
	public void removed() {
		if (open == this) {
			open = null;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		if (query.isBlank()) {
			graphics.centeredText(this.font, Component.translatable("screen.memorypalace.search_help"),
					this.width / 2, 60, 0xFFA0A0A0);
		} else if (searched && results.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("screen.memorypalace.search_none"),
					this.width / 2, 60, 0xFFA0A0A0);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
