<p align="center">
  <img src="art/icon/icon-512.png" alt="Memory Palace icon" width="160">
</p>

<h1 align="center">Memory Palace</h1>

<p align="center">
  Place Markdown / LaTeX notes on bookshelves in your Minecraft world and build your own memory palace.<br>
  Minecraft Java Edition 26.3 · Fabric · MIT
</p>

English | [简体中文](README.zh-CN.md)

<p align="center">
  <a href="../../releases/latest">Download the latest release</a>
</p>

---

"The third bookshelf on the library's second floor" can become the physical address of a note. The world stores only **pointers**; the notes themselves always remain ordinary `.md` files alongside the world data:

- If a creeper destroys a bookshelf, your notes are safe. If you uninstall the mod, the books remain ordinary written books, with the corresponding filenames on their pages.
- The note vault is an ordinary folder. Open it in Obsidian or Typora to edit directly; changes appear immediately in the game.
- Backing up your notes means backing up one folder.

Inspired by [ins@aamanda.li](https://www.instagram.com/aamanda.li)'s [meme reel](https://www.instagram.com/reel/Dd4gs6UTB0w/).

## Features

**Reading and writing**

- **Note books**: ordinary written books, placed in vanilla chiseled bookshelves (six books per shelf) or on lecterns. Right-click to read; sneak + right-click to remove.
- **A reader styled like vanilla books**: two facing pages, with independently adjustable page and font sizes.
- **Markdown**: headings, lists, task lists, block quotes, code blocks, tables, links, and local images (`![description](image.png)` and Obsidian's `![[image.png]]`).
- **Links between notes**: supports Obsidian's `[[note name]]`, `[[folder/note name|display text]]`, and `[[note name#heading]]`, as well as `[text](folder/note.md)`. Click a link on the page to open the corresponding note. Lookup follows Obsidian's rules, preferring a note in the same folder when names match. Web links ask for confirmation before opening in your browser. `/palace backlinks <note>` lists all notes linking to the current note (encrypted note bodies cannot be read by the server and are excluded).
- **LaTeX**: inline formulas with `$...$` and display formulas with `$$...$$`.
- **In-game editor**: edit on the left page with a live preview on the right. `Ctrl+S` saves, `Ctrl+Z` undoes, and `Ctrl+Y` (or `Ctrl+Shift+Z`) redoes. A conflict prompt appears if the file has changed externally.

**Memory palace**

- **Quick capture**: press `K` to record an idea in the day's `收件箱/yyyy-MM-dd.md` and organize it later. The literal folder name `收件箱` means "inbox".
- **Find notes**: press `J` to search titles, folders, and text. Select a note, then follow a light beam and directional arrow, including vertical distance, to its bookshelf.
- **Book titles at the crosshair**: point at a bookshelf slot to see which note the book represents.
- **`/palace where`**: find the bookshelves and lecterns holding a note. Click coordinates to fill in a teleport command in chat.

**Security**

- **Encrypted notes**: Argon2id + AES-256-GCM. Passwords are not stored, and decryption takes place only on the client. Optional recovery keys and automatic locking after inactivity.
- **Automatic backups**: on each world entry, the vault is backed up outside the world save, so deleting the world does not also delete its note backups.
- **Multiplayer**: control who can read and edit each note on LAN worlds and servers, and share with specific players (disabled by default; see below).

## Installation

Requirements:

- Minecraft Java Edition **26.3**
- [Fabric Loader](https://fabricmc.net/use/installer/) 0.20.1 or later
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25 (included with the official launcher)
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu), to open settings from the mod list

Download `memorypalace-x.y.z.jar` from [Releases](../../releases) and place it in `.minecraft/mods/` alongside Fabric API.

## Quick start

1. Enter a world and run `/palace new math/eigenvalues`. This creates a note, adds its book to your inventory, and opens the editor.
2. Write a few lines of Markdown and press `Ctrl+S` to save.
3. Put the book in a chiseled bookshelf. Right-click its slot with an empty hand to read it.
4. To write on your computer, open `saves/<world>/memory-palace/` as a vault in Obsidian. External changes appear immediately in the game.
5. To import existing `.md` files, place them in this folder, run `/palace rescan`, then use `/palace give <path>` to obtain their books.

## Keys and commands

| Key | Action |
|---|---|
| `M` | Open the note window |
| `K` | Quick capture |
| `J` | Find notes |

Change key bindings under "Options → Controls → Key Binds → Memory Palace". Conflicts with other bindings are reported when you enter a world.

| Command | Description |
|---|---|
| `/palace give <note or folder>` | Obtain note books; a folder gives a book for each note inside, up to 36 at a time |
| `/palace new <path>` | Create a note and edit it in the game |
| `/palace list [folder]` | List notes |
| `/palace where <note>` | Find the bookshelves and lecterns holding a note |
| `/palace open <note>` | Open a note directly |
| `/palace backlinks <note>` | List notes linking to this note; click to open them |
| `/palace share <player\|everyone> read\|edit <note>` | Share your note with others (read-only or editable) |
| `/palace unshare <player\|everyone> <note>` | Stop sharing |
| `/palace newsecret <path>` | Create an encrypted note |
| `/palace encrypt <note>` | Encrypt a note |
| `/palace decrypt <note>` | Remove encryption: opens the note; click "Decrypt" below the book, then enter your password to confirm |
| `/palace lockfolder <folder>` | Set a separate folder password and encrypt its notes with that password |
| `/palace folderpassword <folder>` | Change a folder password |
| `/palace unlockfolder <folder>` | Remove a folder password and return its notes to plain text |
| `/palace history <note>` | View version history; click "View" to open read-only or "Restore" to restore a version (requires permission to edit the note) |
| `/palace sharepassword <note>` | Set or replace a reading password for one of your encrypted notes; anyone who knows it can read the note, but cannot edit it |
| `/palace unsharepassword <note>` | Remove the reading password |
| `/palace lock` | Lock encrypted notes immediately, including unlocked folders and entered reading passwords |
| `/palace rescan` | Rescan the vault (administrator) |
| `/palace visibility [everyone\|private]` | View or change this world's note visibility (administrator) |
| `/palace reload` | Reload the server configuration file (administrator) |

Note paths in commands can omit `.md` and can contain spaces and Chinese characters. Press `Tab` to complete note paths in commands for obtaining books, finding locations, sharing, and similar operations.

## Items and recipes

Memory Notes are still vanilla written books. No new items are registered, so they remain books when the mod is removed, and players without the mod can join the server. They carry metadata and use custom inventory textures: ordinary notes have a lapis-blue cover with a gold emblem; encrypted notes also have an iron plate with a keyhole and an iron clasp. Clients without the mod see ordinary written books.

| Recipe (shapeless) | Result | Right-click |
|---|---|---|
| Book and quill + lapis lazuli | Memory Note (Blank) | Create a note in your personal area (`Memory Notes/date time`), turn the book into that note's book, and open the editor |
| Blank Memory Note + iron ingot + tripwire hook | Locked Note (Blank) | As above, then immediately encrypt the new note with your vault password |
| Note book + iron ingot + tripwire hook | Note book pending lock | The author encrypts the note by right-clicking (the server has no key, so encryption must run on the author's client); other players open it normally |
| Note book + blank Memory Note | Copy (the original book stays in the crafting grid; the blank Memory Note is consumed) | Taking the result creates an independent note (`Copies/original filename Copy`); the item you receive is the new note's book. Encrypted notes are also copied immediately, requesting a password when needed and re-encrypting with your key. If `Copies/` has a folder password, right-click the copy after taking it to finish. Shift-clicking an encrypted copy out of the inventory's 2×2 crafting grid also requires a right-click to finish. Only players who can read the original can copy it |

The default folders for blank notes and copies follow the host system's language: `Memory Notes/` and `Copies/` on non-Chinese systems, and `记忆笔记/` and `抄本/` on Chinese systems.

- These are "special recipes", like vanilla written-book copying. They do not appear in the recipe book; arrange the ingredients as shown above.
- Creating and copying notes require the create permission (`memorypalace.create`) and count toward the per-player note limit.
- If the destination `Copies/` folder has a folder password, the server first creates an empty placeholder. The content is encrypted with the folder password on your client before being saved, and the copy becomes a note book only after saving succeeds. Plain text is never written to that folder. If you cancel or saving fails, right-click the copy again to continue copying into the same note. If the placeholder has been changed externally, it is left intact and a new one is created.
- Vanilla written-book copying creates books that still point to the same note (linked copies). Use the copy recipe for an independent note.
- Encryption cannot be removed through crafting. Click "Decrypt" in the open note.

## Version history

Each time you save a note in the game, its previous content is retained in `.palace/history/<note ID>/`. `/palace history <note>` lists the versions with their added and removed line counts. Click "View" to open one read-only, or "Restore" to restore it. Pagination is available when there are more than 10 versions.

- The latest version stores the full text; earlier versions store only their differences from the following version, using little space.
- Retention: keep all of the latest 30 versions, one per hour for the last 7 days, and one per day for the last year, up to 4 MB per note. Frequent saves only replace the most recent versions and do not push out older history.
- Restoring also counts as a save. The content before restoration is always retained, so an accidental restore can be reversed.
- Only saves made in the game are recorded. Changes made in Obsidian or other external editors are not recorded here; full-vault backups still apply.
- Only players who can edit a note can view or restore its history. Read-only users cannot access it.
- Encrypted note history stores only ciphertext. When a plain-text note is encrypted, its previous plain-text history is deleted. Restoring an encrypted note re-encrypts the old version with the current protection, so a reading password that has been removed does not return with an old version.
- History does not count toward the per-player note quota.

## Note vault

```
saves/<world>/memory-palace/      ← Note vault; open directly in Obsidian
├─ math/eigenvalues.md
├─ 收件箱/2026-10-27.md           ← Quick capture
├─ players/<player UUID>/…       ← Each player's personal area in multiplayer, including their inbox
└─ .palace/                      ← Mod data: location records, keyrings, world settings
```

Each note begins with front matter. The mod identifies it using `palace-id`, so **renaming files or moving them between folders externally does not break their in-game links**:

```markdown
---
palace-id: 7f3a2c1e-…
palace-owner: <author UUID>       # These three fields are used only in multiplayer and are optional
palace-read: everyone            # Readers: everyone or comma-separated player UUIDs
palace-edit: <UUID>, <UUID>       # Editors other than the author
---
# Eigenvalues
$$A\mathbf{v} = \lambda \mathbf{v}$$
```

## Multiplayer (LAN and servers)

**Disabled by default.** When disabled, only the local host can use notes; other players cannot see any of them. Singleplayer is unaffected. Dedicated servers have no local host, so no one can use notes while the feature is disabled.

### Enabling multiplayer

Edit `config/memorypalace-server.toml`, which is generated with comments when a world or server first starts. Set `multiplayer` to `true`, then have an administrator run `/palace reload` (a dedicated server requires a restart). If the configuration is invalid or cannot be read, multiplayer is treated as disabled.

| Setting | Default | Description |
|---|---|---|
| `multiplayer` | `false` | Master switch for multiplayer |
| `default_visibility` | `"private"` | Who can read notes without `palace-read`: `"private"` (the author and people granted access) or `"everyone"` (all players). A world-specific setting made with `/palace visibility` takes precedence |
| `max_notes_per_player` | `200` | Maximum notes each player can create; `0` means unlimited |
| `max_note_chars` | `200000` | Maximum characters an ordinary player can save in one note; `0` means unlimited |

Administrators are exempt from the note-count and character limits.

### Permission nodes

Permissions are checked with [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api), which is bundled and does not need a separate installation. With a permissions mod such as LuckPerms, server owners can assign them by player group. Otherwise, the defaults below apply.

| Node | Default | Purpose |
|---|---|---|
| `memorypalace.admin` | Local host; operators at level 2 or above | Obtain books for any note, create notes anywhere, rescan, change visibility, and reload configuration |
| `memorypalace.create` | Everyone | Create notes in the player's personal area |
| `memorypalace.give` | Everyone | Obtain books for notes the player can read |
| `memorypalace.share` | Everyone | Share and stop sharing the player's own notes |

### Rules

- **Creation**: ordinary players create notes under their own `players/<UUID>/` area and are recorded as the author. Paths entered in commands are looked up in that personal area first. The local host continues to create notes at the specified location.
- **Reading and editing**: the author can read and edit; players in `palace-edit` can both edit and read; players in `palace-read` can read. Notes without an author, such as older notes, host-created notes, and inbox notes, belong to the local host, or to administrators on a dedicated server.
- **Sharing**: only the author can share a note. Encrypted notes and inbox notes cannot be shared with `share`; encrypted notes can instead use a reading password, as described under "Encryption". `/palace unshare everyone <note>` makes a public note private again. There is no deny list: removing one player does not prevent them from reading a note that is still public.
- **A book is an address, not a key**: obtaining someone else's note book grants no access. The server checks permissions on every request. In LAN worlds or on servers, private note titles display only a placeholder such as "[Private Note] #7f3a".
- **Images**: an image must be in the same area as the note that references it: either the same player's personal area or both outside personal areas. If the image's folder or an ancestor folder contains notes you cannot read, you cannot view that image. Keep private notes in subfolders.
- **Concurrent editing**: real-time collaborative editing is not supported. If two players edit the same note, the second person to save receives a conflict prompt.
- **Inbox**: each player has one. The host's is in `收件箱/`; other players' inboxes are in `players/<UUID>/收件箱/` and are visible only to their owners. Once an encryption password is set, quick notes are encrypted automatically, so even server administrators cannot read their content (see "Encryption").
- **Encryption**: each player has a keyring and a vault password. The host's keyring is at `.palace/keyring`; other players' keyrings are at `.palace/keyrings/<UUID>`. Players can encrypt only notes they authored. After encryption, only the author can open the note, and earlier sharing permissions no longer grant access.
- External changes to `palace-read` / `palace-edit` take effect within about 2 seconds. Files newly added to the vault require an administrator to run `/palace rescan`.

### Notes

- **Server administrators can read unencrypted notes directly from disk**; on a LAN world, this means the host. Only encryption protects against administrators.
- Book titles are written into the books themselves. **Books obtained in a purely singleplayer session retain their actual titles.** Put away books containing private notes before opening the world to LAN.

## Encryption

- **Inbox encryption**: quick capture (`K`) encrypts without requiring a password at capture time. The keyring contains an inbox key pair: the public key encrypts entries, and entering your password when opening the inbox unlocks the private key for decryption. The server can see only which days have entries, how many entries there are, and their approximate lengths. After you organize and save an inbox note, it becomes a regular encrypted note; later entries that day go into `yyyy-MM-dd-2.md`. If you have not set a password, quick notes remain plain text. Inbox encryption can be disabled in settings.
- **Protection against public-key substitution**, on other people's servers: a malicious server could replace your inbox public key with its own. The client remembers the fingerprint of the first key it sees. If it later differs, a two-step confirmation appears, with saving disabled by default. If you know the reason, such as resetting your keyring or changing computers, you can select "Trust new inbox key" in settings.
- One vault password controls all your encrypted notes; in multiplayer, each player has their own.
- **Separate folder passwords**: `/palace lockfolder <folder>` sets an independent password for a folder, such as a journal. Its notes, including those in subfolders, are encrypted with the folder key. **The vault password cannot open them**, and unlocking your regular encrypted notes does not reveal the journal. Notes subsequently created in that folder are encrypted automatically, and the server refuses to store plain text there. The lock file (`.palace-lock`) is stored inside the folder and moves with it when the folder is moved or renamed externally. Players can lock only folders in their personal area; the host can lock folders outside personal areas. All notes in the folder must belong to the player setting the password. Locks cannot be nested above or below an existing locked folder. A recovery key can also be generated when setting the password.
  - Notes are encrypted one at a time after the lock is set, at roughly one per second because of the server's save limit. The final report lists successes, failures, and unprocessed notes. If locking or disconnection interrupts the process, run `/palace lockfolder <folder>` again to continue with the remaining notes. Already encrypted notes are skipped.
  - `/palace folderpassword <folder>` changes the folder password after you enter the current one.
  - `/palace unlockfolder <folder>` removes the folder password and decrypts the notes to plain text one by one. The lock is deleted only after every note is saved. If any note fails, the lock remains, and notes still encrypted continue to open with the folder password. Run the command again to continue.
  - If you forget a folder password, click "Forgot password?" while unlocking, changing, or removing it, then reset it with the recovery key saved when the password was set. Without a recovery key, recovery is impossible.
  - Individual notes in the folder cannot have their encryption removed separately; the whole folder must be processed. Passwords are not stored in any form. A forgotten password can be reset only with the recovery key. Without a recovery key, forgetting the password permanently prevents access to the encrypted notes.
- **Per-note reading passwords**: `/palace sharepassword <note>` sets a separate reading password for one of your encrypted notes. It is independent of the vault password and must be at least 8 characters; a sentence is recommended. Give the book to a friend or place it on a lectern or bookshelf. Right-clicking opens a password prompt. With the correct password, the friend can **read but not edit** the note.
  - The password is used only on the two clients. The server checks a verification code derived from it and cannot see the password or the note body. Each player gets at most 5 incorrect attempts per note per minute, followed by waits of 1, 2, 4, and so on minutes, up to 1 hour.
  - After a correct entry, the password is not requested again during the current server session. Locking, including automatic locking after inactivity, or leaving the world requires it to be entered again.
  - Running `sharepassword` again replaces the password and immediately invalidates the old one. `/palace unsharepassword <note>` removes it. Both changing and removing the password rotate the content key and re-encrypt the note, so keys held by readers using the old password cannot open new content. However, **content already read cannot be taken back**.
  - Setting a reading password on an older encrypted note also upgrades that note to the new format; only that note is changed. Notes in password-protected folders can also have reading passwords. Friends need only the reading password, not the folder password.
  - Readers using a reading password do not receive live updates from the author's later edits. They must reopen the note to see new content.
  - Do not send passwords in game chat: the server can read chat logs. Server administrators who obtain a note file can attempt to guess the password offline, so use a sufficiently long reading password.
- A recovery key is equivalent to a second key. Keep it secure.
- **Security keys (Windows only)**: click "Security key" in settings and verify your vault password. Follow the Windows prompts to insert a FIDO2 security key such as a YubiKey, enter its PIN, and touch it; this is requested twice during registration. Afterwards, click "Use security key" when unlocking encrypted notes instead of entering your password. The password and recovery key remain usable. The key must support the hmac-secret extension, and its PIN is required on every use. Only one key can be registered at a time; remove it before adding a replacement. The offline decryption tool does not support security keys; use your password or recovery key.
- Filenames and images referenced by encrypted notes are not encrypted. Encrypted note books display the filename because the heading in the note body is encrypted.
- Changing a password does not invalidate old backups. An old backup and its old password can still decrypt the notes as they were then.
- **Offline decryption tool**: the companion `memory-palace-decrypt-<version>.jar` tool requires Java 25 and can decrypt notes without starting the game. Double-click it, select the `memory-palace` folder or its containing world save folder, enter your vault password, folder password, or recovery key, then click "Decrypt". Notes that can be decrypted are saved as plain text in a sibling folder with the suffix " (Decrypted)". Original files are not modified. Command-line use is also supported: `java -jar memory-palace-decrypt-<version>.jar <notes-folder>`. Anyone can read the plain-text files; delete them promptly after reviewing them.

## Backups

- Each time you enter a world, the vault is backed up outside the world save to `.minecraft/memory-palace-backups/<world>/`. The latest 30 backups are retained; no backup is created if the content has not changed.
- Backups are incremental: each unique file content is stored once in `objects/`, and each backup records only a manifest in `snapshots/`. Unchanged files are not stored repeatedly, using much less space than making a full archive each time. When old backups are removed, content no longer referenced by any backup is also deleted.
- Each backup also creates a complete `最新备份.zip` archive ("latest backup"), replacing the previous one. It can be extracted directly without a special tool.
- **Browse and restore in the game**: on the "Notes & Backups" settings page, back up immediately, open the backup folder, or click "Browse and restore backups" to view all backups and restore one to the vault. Current content is backed up first. Files that currently exist but are absent from the backup, or have different content, are moved to `.palace/trash`, so restoration can still be reversed. Available only in singleplayer or a LAN world you host.
- **Restore with the offline tool**, for example after a world has been deleted: open `memory-palace-decrypt-<version>.jar`, select the relevant world's folder under `memory-palace-backups`, choose a backup version, and click "Restore from Backup". The restored vault is saved in a new sibling folder without overwriting any files. Encrypted notes remain encrypted and can then be decrypted in the tool. Command-line use: `java -jar memory-palace-decrypt-<version>.jar --restore <backup-folder> [backup-version]`.
- ZIP backups from earlier versions are not deleted and can still be extracted directly.
- Backups are named using UTC, independent of time zones and daylight saving time.
- Quick capture files are organized by the player's local date. The first entry each day, and entries after a time-zone change, include the time zone, such as `(UTC+8)`.

## Building from source

Requires JDK 25.

```bash
./gradlew build        # Output in build/libs/
./gradlew runClient    # Start the development client
```

## Third-party libraries

The mod JAR bundles the following libraries:

- [commonmark-java](https://github.com/commonmark/commonmark-java) (BSD-2-Clause)
- [JLaTeXMath](https://github.com/opencollab/jlatexmath) (GPL-2.0 with Classpath Exception)
- [Password4j](https://github.com/Password4j/password4j) (Apache-2.0)
- [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (MIT)
- [NightConfig](https://github.com/TheElectronWill/night-config) (LGPL-3.0)
- [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils) (Apache-2.0)

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the complete notices.

## License

[MIT](LICENSE)

This project was developed with assistance from [Claude Code](https://claude.com/claude-code) and OpenAI Codex.
