package com.memorypalace.core.vault;

import com.memorypalace.core.vault.NoteAccess.Rules;
import com.memorypalace.core.vault.NoteAccess.Visibility;
import com.memorypalace.core.vault.NoteAccess.Who;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.memorypalace.core.vault.NoteAccess.Visibility.EVERYONE;
import static com.memorypalace.core.vault.NoteAccess.Visibility.PRIVATE;
import static org.junit.jupiter.api.Assertions.*;

class NoteAccessTest {
	private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID CAROL = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static final Who HOST = new Who(UUID.fromString("99999999-9999-9999-9999-999999999999"), true);
	private static final Who ALICE_PLAYER = new Who(ALICE, false);
	private static final Who BOB_PLAYER = new Who(BOB, false);
	private static final Who CAROL_PLAYER = new Who(CAROL, false);

	private static Rules rules(String... keyValues) {
		Map<String, String> fields = new java.util.LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			fields.put(keyValues[i], keyValues[i + 1]);
		}
		return NoteAccess.parse(fields);
	}

	@Test
	void parsesOwnerAndLists() {
		Rules r = rules("palace-owner", ALICE.toString(),
				"palace-read", "[" + BOB + ", '" + CAROL + "']",
				"palace-edit", "everyone");
		assertEquals(ALICE, r.owner());
		assertEquals(Set.of(BOB, CAROL), r.read());
		assertEquals(Set.of(Rules.EVERYONE_ID), r.edit());
	}

	@Test
	void ignoresMalformedValues() {
		Rules r = rules("palace-owner", "not-a-uuid", "palace-read", "1-1-1-1-1, ,bob", "palace-edit", "");
		assertNull(r.owner(), "写错的作者当成无主");
		assertEquals(Set.of(), r.read(), "写了 palace-read 但一个都认不出：谁也不额外放行（不会退回可见性）");
		assertEquals(Set.of(), r.edit());
		assertNull(rules("palace-owner", Rules.EVERYONE_ID.toString()).owner(), "全零 UUID 不能当作者");
	}

	@Test
	void unownedNotesBelongToAdmins() {
		Rules r = Rules.NONE;
		assertTrue(NoteAccess.canEdit(r, false, HOST));
		assertFalse(NoteAccess.canEdit(r, false, ALICE_PLAYER));
		assertTrue(NoteAccess.canRead(r, false, ALICE_PLAYER, EVERYONE), "公开世界里无主笔记大家都能读");
		assertFalse(NoteAccess.canRead(r, false, ALICE_PLAYER, PRIVATE));
		assertTrue(NoteAccess.canRead(r, false, HOST, PRIVATE));
	}

	@Test
	void visibilityDecidesWhenReadIsNotWritten() {
		Rules r = rules("palace-owner", ALICE.toString());
		assertTrue(NoteAccess.canRead(r, false, BOB_PLAYER, EVERYONE));
		assertFalse(NoteAccess.canRead(r, false, BOB_PLAYER, PRIVATE));
		assertTrue(NoteAccess.canRead(r, false, ALICE_PLAYER, PRIVATE), "作者自己永远能读");
		assertFalse(NoteAccess.canEdit(r, false, BOB_PLAYER), "能读不等于能改");
		assertTrue(NoteAccess.canEdit(r, false, ALICE_PLAYER));
	}

	@Test
	void explicitReadListOverridesVisibility() {
		Rules r = rules("palace-owner", ALICE.toString(), "palace-read", BOB.toString());
		assertTrue(NoteAccess.canRead(r, false, BOB_PLAYER, PRIVATE));
		assertFalse(NoteAccess.canRead(r, false, CAROL_PLAYER, EVERYONE), "写了名单就按名单，公开世界也不例外");
		assertTrue(NoteAccess.canRead(rules("palace-read", "everyone"), false, CAROL_PLAYER, PRIVATE));
	}

	@Test
	void editorsCanAlsoRead() {
		Rules r = rules("palace-owner", ALICE.toString(), "palace-read", CAROL.toString(), "palace-edit", BOB.toString());
		assertTrue(NoteAccess.canEdit(r, false, BOB_PLAYER));
		assertTrue(NoteAccess.canRead(r, false, BOB_PLAYER, PRIVATE));
		assertFalse(NoteAccess.canEdit(r, false, CAROL_PLAYER));
	}

	@Test
	void adminsGetNoSpecialPowersOverOwnedNotes() {
		Rules r = rules("palace-owner", ALICE.toString());
		assertFalse(NoteAccess.canRead(r, false, HOST, PRIVATE));
		assertFalse(NoteAccess.canEdit(r, false, HOST));
	}

	@Test
	void ownerOnlyNotesIgnoreSharingAndVisibility() {
		// 收件箱、加密笔记：写了 everyone 也没用
		Rules r = rules("palace-owner", ALICE.toString(), "palace-read", "everyone", "palace-edit", "everyone");
		assertFalse(NoteAccess.canRead(r, true, BOB_PLAYER, EVERYONE));
		assertFalse(NoteAccess.canEdit(r, true, BOB_PLAYER));
		assertTrue(NoteAccess.canRead(r, true, ALICE_PLAYER, EVERYONE));
		assertFalse(NoteAccess.canRead(Rules.NONE, true, ALICE_PLAYER, EVERYONE), "无主的收件箱只有管理员能看");
		assertTrue(NoteAccess.canRead(Rules.NONE, true, HOST, EVERYONE));
	}

	@Test
	void titleIsPublicOnlyWhenEveryoneCanRead() {
		assertTrue(NoteAccess.titleIsPublic(Rules.NONE, false, EVERYONE));
		assertFalse(NoteAccess.titleIsPublic(Rules.NONE, false, PRIVATE));
		assertFalse(NoteAccess.titleIsPublic(rules("palace-read", BOB.toString()), false, EVERYONE));
		assertTrue(NoteAccess.titleIsPublic(rules("palace-read", "everyone"), false, PRIVATE));
		assertTrue(NoteAccess.titleIsPublic(rules("palace-edit", "everyone"), false, PRIVATE));
		assertFalse(NoteAccess.titleIsPublic(Rules.NONE, true, EVERYONE), "收件箱、加密笔记的标题永远不公开");
	}

	@Test
	void visibilityParsing() {
		assertEquals(PRIVATE, Visibility.parse(" Private "));
		assertEquals(EVERYONE, Visibility.parse("everyone"));
		assertEquals(EVERYONE, Visibility.parse(null));
		assertEquals(EVERYONE, Visibility.parse("乱写"));
	}

	@Test
	void readNoteUsesPermissionsFromTheFileRightNow(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("分享.md");
		Files.writeString(file, "---\npalace-owner: " + ALICE + "\npalace-read: " + BOB + "\n---\n# 分享\n", StandardCharsets.UTF_8);
		Vault vault = new Vault(dir);
		vault.scan();
		UUID id = vault.resolve("分享").orElseThrow().id();
		assertTrue(vault.readNote(id).orElseThrow().note().canRead(BOB_PLAYER, EVERYONE));

		// 在外部把 Bob 从名单里删掉，不重新扫描：下一次读就要按新权限
		String text = Files.readString(file, StandardCharsets.UTF_8).replace("palace-read: " + BOB, "palace-read: " + CAROL);
		Files.writeString(file, text, StandardCharsets.UTF_8);
		Vault.Read read = vault.readNote(id).orElseThrow();
		assertFalse(read.note().canRead(BOB_PLAYER, EVERYONE));
		assertTrue(read.note().canRead(CAROL_PLAYER, EVERYONE));
		assertFalse(vault.find(id).orElseThrow().canRead(BOB_PLAYER, EVERYONE), "索引也跟着刷新了");
	}

	@Test
	void refreshChangedPicksUpExternalPermissionEdits(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("公开.md");
		Files.writeString(file, "---\npalace-owner: " + ALICE + "\n---\n# 公开\n", StandardCharsets.UTF_8);
		Vault vault = new Vault(dir);
		vault.scan();
		UUID id = vault.resolve("公开").orElseThrow().id();
		assertTrue(vault.find(id).orElseThrow().canRead(BOB_PLAYER, EVERYONE));

		String text = Files.readString(file, StandardCharsets.UTF_8).replace("palace-owner: " + ALICE,
				"palace-owner: " + ALICE + "\npalace-read: " + CAROL);
		Files.writeString(file, text, StandardCharsets.UTF_8);
		// 有的文件系统修改时间只精确到秒：明确改一下，确保"变了"
		Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
		vault.refreshChanged();
		assertFalse(vault.find(id).orElseThrow().canRead(BOB_PLAYER, EVERYONE), "不用整库重扫也按新权限");
		assertTrue(vault.find(id).orElseThrow().canRead(CAROL_PLAYER, EVERYONE));
	}

	@Test
	void writeBodyChecksPermissionsInTheSameStep(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("合写.md");
		Files.writeString(file, "---\npalace-owner: " + ALICE + "\npalace-edit: " + BOB + "\n---\n# 合写\n", StandardCharsets.UTF_8);
		Vault vault = new Vault(dir);
		vault.scan();
		UUID id = vault.resolve("合写").orElseThrow().id();
		assertEquals(Vault.WriteResult.OK, vault.writeBody(id, "# 合写\nBob 写的", null, n -> n.canEdit(BOB_PLAYER)));

		// 外部收回 Bob 的编辑权限（没重新扫描），他再保存就被拒绝，文件不动
		String text = Files.readString(file, StandardCharsets.UTF_8).replace("palace-edit: " + BOB, "palace-edit: " + CAROL);
		Files.writeString(file, text, StandardCharsets.UTF_8);
		assertEquals(Vault.WriteResult.DENIED, vault.writeBody(id, "# 合写\n偷偷改", null, n -> n.canEdit(BOB_PLAYER)));
		assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("Bob 写的"));
	}

	@Test
	void unknownIdsDoNotRescanEveryTime(@TempDir Path dir) throws IOException {
		Vault vault = new Vault(dir);
		vault.scan();
		assertTrue(vault.readNote(UUID.randomUUID()).isEmpty());
		// 第一次不认识的 ID 扫描过了；5 秒内再来一个随机 ID 不扫描——放进来的新文件也就暂时找不到
		UUID id = UUID.randomUUID();
		Files.writeString(dir.resolve("新.md"), "---\npalace-id: " + id + "\n---\n# 新\n", StandardCharsets.UTF_8);
		assertTrue(vault.readNote(id).isEmpty());
		vault.scan();
		assertTrue(vault.readNote(id).isPresent(), "手动重扫后找得到");
	}

	@Test
	void vaultScanReadsRulesAndInboxIsOwnerOnly(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("公开.md"), "---\npalace-owner: " + ALICE + "\n---\n# 公开\n", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve(Inbox.FOLDER));
		Files.writeString(dir.resolve(Inbox.FOLDER).resolve("2026-10-27.md"),
				"---\npalace-read: everyone\n---\n# 收件箱\n", StandardCharsets.UTF_8);
		Vault vault = new Vault(dir);
		vault.scan();
		NoteSummary note = vault.resolve("公开").orElseThrow();
		assertEquals(ALICE, note.access().owner());
		assertTrue(note.canRead(BOB_PLAYER, EVERYONE));
		assertFalse(note.canEdit(BOB_PLAYER));
		NoteSummary inbox = vault.resolve(Inbox.FOLDER + "/2026-10-27").orElseThrow();
		assertTrue(inbox.inbox());
		assertFalse(inbox.canRead(BOB_PLAYER, EVERYONE), "收件箱写了 everyone 也不公开");
		assertTrue(inbox.canRead(HOST, EVERYONE));
		assertFalse(inbox.titleIsPublic(EVERYONE));
	}
}
