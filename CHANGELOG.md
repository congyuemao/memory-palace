# Changelog
[简体中文](CHANGELOG.zh-CN.md)

## 1.0.1

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

**Improvements**

- **Localization**: added interface translations beyond English: Traditional Chinese, Japanese, Korean, Russian, Spanish, French, German, and Brazilian Portuguese. These translations are machine-generated; corrections are welcome.
- Item names, item descriptions, note book pages, error messages, and the Mod Menu description now follow the game language.
- Added English to the offline tool and the comments in the server configuration file.
- New worlds generate both "欢迎.md" and "Welcome.md".
- The mod JAR now includes the MIT license and third-party license notices.

**Notes**

- The server and client must use the same version.
- The inbox folder name ("收件箱") and backup filename ("最新备份.zip") remain unchanged for compatibility with existing data.

## 1.0.0 (2026-10-30)

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

**Downloads**

- `memorypalace-1.0.0.jar`: the mod itself; place it in the `mods` folder.
- `memory-palace-decrypt-1.0.0.jar`: the offline tool, requiring Java 25. Decrypt notes or restore backups without starting the game. Double-click to open.

**New feature: note links**

- **Link syntax**: supports Obsidian-style `[[note name]]`, `[[folder/note name|display text]]`, and `[[note name#heading]]`, as well as Markdown links such as `[text](path.md)`. Click a link on a book page to open the corresponding note. Web links request confirmation before opening in your browser.
- **Link completion**: after typing `[[` in the editor, note name suggestions appear at the top of the right page. Use ↑↓ to select, Tab or Enter to insert, and Esc to dismiss.
- **Update links when renaming**: renaming a note with `/palace rename` automatically updates links to it in other plain-text notes. Only notes you have permission to edit are updated.
- **Backlinks**: when reading a note, the end of its body lists notes that link to it. Click an entry to open it, or use `/palace backlinks <note>` to view the list.
- **Broken link indicators**: links to missing notes appear in dark red. Click and confirm to create the note and open the editor.

**Improvements**

- **Copy workflow**: place the original book and a blank Memory Note in a crafting table. The item taken from the result slot is already the completed new note. The original remains in the crafting grid, and the blank note is consumed. This also applies to encrypted notes.
- **Performance**: vault scanning, searching, backlinks, and note creation are significantly faster. With 2,000 notes, the initial scan dropped from about 80 seconds to about 1 second, and searches from about 2.5 seconds to about 60 milliseconds.
- **Stability and security**: added rate limits and a server-wide concurrency limit for link requests. Vault scans read files in batches to reduce memory use, and file modification times are checked more precisely.
- Internal code was refactored and decoupled to simplify future maintenance.

**Notes**

- The server and client must use the same version.
- This release is compatible with notes, encrypted data, and backups from 0.4.x. No migration is required.



## 0.4.1 (2026-10-29)

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

**Downloads**

- `memorypalace-0.4.1.jar`: the mod itself; place it in the `mods` folder.
- `memory-palace-decrypt-0.4.1.jar`: the offline tool, requiring Java 25. Decrypt notes or restore backups without starting the game. Double-click to open.

**New features**

- **Incremental backups**: backups made on world entry now deduplicate content. Unchanged files are no longer stored repeatedly, greatly reducing disk usage. The latest 30 backups are retained; content no longer used after older backups are removed is also deleted.
- **Latest backup ZIP**: each backup also saves a complete "最新备份.zip", which can be extracted directly without a special tool.
- **Browse and restore backups in the game**: the "Notes & Backups" settings page lets you back up immediately, open the backup folder, or browse all backups and restore one to the vault. Current content is backed up first. Replaced or extra files are moved to `.palace/trash`, so restoration can still be reversed. Available only in singleplayer or a LAN world you host.
- **Offline backup restoration**: even after a world has been deleted, the offline tool can restore a backup as a complete vault, which can then be decrypted.
- **Tabbed settings**: settings are divided into three pages, "Reading & Editing", "Encryption & Unlocking", and "Notes & Backups", to reduce crowding.

**Improvements**

- Encrypted note books now consistently display the filename, path, and author in both singleplayer and multiplayer. The note content remains encrypted; only the filename is visible.

**Notes**

- ZIP backups from earlier versions are not deleted and can still be extracted directly.
- The server and client must use the same version.



## 0.4.0 (2026-10-29)

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

This release includes all features added since the 0.3.0 prerelease. The main features have been tested in the game.

**Downloads**

- `memorypalace-0.4.0.jar`: the mod itself; place it in the `mods` folder.
- `memory-palace-decrypt-0.4.0.jar`: the offline decryption tool, requiring Java 25. Decrypt notes without starting the game. Double-click to open.

**New features**

- **Unlock with a security key (Windows only)**: click "Security key" in settings to add a FIDO2 security key such as a YubiKey. Once registered, click "Use security key" when unlocking encrypted notes instead of entering your password. The key's PIN is required on every use. Passwords and recovery keys remain usable. The key must support the hmac-secret extension.
- **Per-note reading passwords**: `/palace sharepassword <note>` sets a separate reading password for one of your encrypted notes. Anyone who knows it can open the note read-only. `/palace unsharepassword <note>` removes it.
- **Version history**: every in-game save retains the previous version; older versions store only differences. Retention keeps all of the latest 30 versions, one per hour for 7 days, and one per day for 1 year, up to 4 MB per note. Use `/palace history <note>` to view or restore versions, with pagination.
- **Autosave**: in settings, disable editor autosave or choose intervals of 1, 5, or 10 minutes (default: 5 minutes). Saving waits until you have stopped typing for 2 seconds. If the file changes outside the game, it is not overwritten; a status message appears instead.
- **Items and recipes**: added Memory Note and Locked Note items, plus four crafting recipes. Copies of ordinary notes complete automatically when placed in the inventory after crafting.
- **New commands**: `/palace newfolder` creates a folder, `/palace rename` renames a note, and `/palace delete` deletes a note or folder. Deletion requires confirmation and moves the content to the `.palace/trash` recycle bin.
- **Open the notes folder**: in singleplayer or a LAN world you host, open the current world's vault directly in your file manager from settings.
- **Encrypted note books**: the book title now shows the filename, and the author shows the note owner's player name. Non-public notes remain hidden. Books in the inventory update immediately when a note is encrypted or renamed.

**Fixes**

- Quick notes could lose their content if the keyring could not be read, encryption failed, the server rejected the write, or the server did not respond for too long. Press `K` to recover and retry; resending does not append a duplicate.
- Right-clicking a blank note or copy could cause the game to clear the new book if it was placed in the held slot.
- Copying an ordinary note into a password-protected Copies folder copied only a page containing the default heading.
- A read failure during backup now aborts the backup instead of producing an incomplete ZIP or cleaning up existing backups.
- The editor's "unsaved" status obscured messages about save failures, file conflicts, and similar issues.

**Notes**

- **The server and client must be upgraded together**: this release's quick-capture message format is incompatible with 0.3.0. Mixing versions prevents quick notes from being saved.
- History saved by older versions can still be viewed and restored.
- The offline decryption tool does not support security keys. Use your password or recovery key.



## 0.3.0 (2026-10-27)

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

> **Prerelease: the new features in this version have only been verified through unit tests and dedicated-server startup checks. They have not yet been tested in the game, and the client interface may have issues.** Try them in a test world and test vault first, and back up important notes beforehand.

**New features**

- **Undo / redo**: `Ctrl+Z` undoes in the editor; `Ctrl+Y` or `Ctrl+Shift+Z` redoes. Continuous typing is grouped into one step; moving the cursor or clicking starts a new step.
- **New MPAL2 encryption format**: each note has a content key, wrapped with the vault password or folder password, laying the groundwork for per-note sharing.
- **Multiplayer: one keyring and inbox per player**: the host's keyring is at `.palace/keyring`; other players' keyrings are at `.palace/keyrings/<UUID>`. Each player's inbox is in their personal area at `players/<UUID>/收件箱/` and is visible only to them.
- **Inbox encryption**: after a password has been set, quick capture with `K` uses public-key encryption without prompting for the password, and the server stores only ciphertext. Enter the password when opening the inbox to decrypt it. Once the day's inbox has been organized and saved as an encrypted note, further entries go into `yyyy-MM-dd-2.md`. On other people's servers, a change to the inbox public key prompts for confirmation; use "Trust new inbox key" in settings when appropriate. Inbox encryption can be disabled in settings.
- **Separate folder passwords**:
  - `/palace lockfolder <folder>` sets a separate password for a folder, such as a journal. Its notes are encrypted with the folder key and cannot be opened with the vault password. New notes created there are encrypted automatically, and the server refuses plain-text saves.
  - `/palace folderpassword <folder>` changes the folder password; it can also be reset with a recovery key.
  - `/palace unlockfolder <folder>` removes the folder password. The lock is deleted only after all notes have been decrypted and saved.
  - Notes are processed one at a time, roughly one per second. The final report lists successful, failed, and unprocessed notes. Running the command again resumes an interrupted operation.

**Fixes**

- **Undo**: two nonadjacent edits were incorrectly grouped into one step.
- **Folder encryption / decryption**: processing more than 10 notes hit the save rate limit, leaving later notes unprocessed with no way to resume.

**Notes**

- **You cannot downgrade directly to 0.2.1**: upgrading adds an inbox key pair (the `box` field) to the keyring. Version 0.2.1 and earlier cannot read this keyring format. Before downgrading, back up `.palace/keyring` and `.palace/keyrings/`.
- **Older encrypted notes are not migrated automatically**: notes encrypted by 0.2.1 and earlier (MPAL1) still open normally, but are not automatically converted to MPAL2. Folder passwords apply only to MPAL2.
- Images are not yet displayed in encrypted notes.
- Canceling the vault password dialog while encrypting an entire folder prevents the success and failure counts from being reported.



## 0.2.1 (2026-10-27)

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

**Fixes**

- **Sharing**: sharing with an individual player, then with `everyone`, and finally running `/palace unshare everyone` also removed that player's individual access. The same happened when making a note public first, sharing individually, and then removing public access; both read-only and edit access were affected. The lists now retain both `everyone` and individually authorized players, for example `palace-read: everyone, <UUID>`, so individual permissions remain after public access is removed.

**Note**: permissions already lost through this issue in 0.2.0 cannot be recovered automatically. Run `/palace share` again.



## 0.2.0 (2026-10-27)

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

**New feature: multiplayer (LAN and servers)**

- **Disabled by default**. Enable it by setting `multiplayer` to `true` in `config/memorypalace-server.toml`, which is generated with comments on first startup. When disabled, only the local host can use notes; singleplayer is unaffected. Apply changes with `/palace reload`.
- **Per-note permissions**: `palace-owner` / `palace-read` / `palace-edit` in note front matter determine who can read and edit. Visibility defaults to private, controlled by `default_visibility` or the world's `/palace visibility` setting.
- **Player-created notes**: `/palace new` creates notes in the player's personal `players/<UUID>/` area with that player as the author. `/palace give` only provides books for notes the player can read.
- **Sharing**: `/palace share <player|everyone> read|edit <note>` and `/palace unshare <player|everyone> <note>`. Only the author can share a note. Encrypted notes and inbox notes cannot be shared.
- **Permission nodes**: `memorypalace.admin`, `memorypalace.create`, `memorypalace.give`, and `memorypalace.share`. The fabric-permissions-api is bundled, so no separate installation is required. With LuckPerms, permissions can be assigned by group.
- Quotas limit notes per player and characters per note for ordinary players. Creating, sharing, obtaining books, opening, and saving are rate-limited.
- Real-time collaborative editing is not supported. If two players edit the same note, the second to save receives a conflict prompt.
- Inbox and encryption features are currently available only to the local host.

**Security**

- The server checks permissions for every message. A note ID alone no longer grants read or write access.
- For non-public notes, book titles and crosshair tooltips display only a placeholder such as "[Private Note] #7f3a" in LAN worlds and on servers.
- Removing encryption now requires the player's explicit confirmation in the open note, preventing a malicious server from tricking the client into disclosing plain text.
- Images must be in the same area as the note referencing them. Players cannot read images from one another's personal areas.
- Added protection against malicious servers exhausting client resources with oversized images, excessively long formulas, or large numbers of image references.
- A configuration read failure always leaves multiplayer disabled.

**Fixes**

- Automatic backups now detect changes by content alone, fixing missed backups when importing old notes, renaming, deleting, or restoring modification times.
- New icon.

**Notes**

- Books obtained in a purely singleplayer session retain their real titles. Put away books containing private notes before opening the world to LAN.
- Server administrators, or the host in a LAN world, can read unencrypted notes directly from disk. Only encryption protects against administrators.



## 0.1.1 (2026-10-26)

For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

**Fixes and improvements**

- **Time zones**: quick capture files use the player's local date. The first entry each day and entries after a time-zone change include the time zone, such as `(UTC+8)`. Half-hour time zones and crossing the International Date Line are supported.
- **Automatic backups**: backups now use UTC filenames and are cleaned up by timestamp. Fixed earlier versions potentially deleting the newest backup when daylight saving time ended or the time zone changed.
- **Security**: all note features, including reading, searching, editing, inbox, crosshair titles, images, and commands, are restricted to the local host in singleplayer. They are disabled in LAN worlds and on servers to prevent other players from reading or modifying notes.
- Searches run one task at a time per player. Quick capture is rate-limited, and the daily inbox limit is approximately 500,000 characters.
- Crosshair title lookup now uses the vanilla block interaction distance.

**Note**: backups created by older versions (`yyyy-MM-dd_HH-mm-ss.zip`) are no longer included in automatic cleanup and can be deleted manually.



## 0.1.0 (2026-10-26)

Initial release. For Minecraft Java Edition 26.3 + Fabric Loader 0.20.1+ + Fabric API.

**Installation**: download `memorypalace-0.1.0.jar` and place it in `.minecraft/mods/` alongside Fabric API. Mod Menu is optional.

**Included features**

- Note books with vanilla chiseled bookshelves and lecterns. Notes are stored as `.md` files alongside the world data and are not lost if a bookshelf is destroyed.
- A reader styled like vanilla books, with adjustable page and font sizes.
- Markdown, LaTeX formulas, and local images.
- An in-game editor with live preview and conflict detection.
- Encrypted notes with Argon2id + AES-256-GCM, optional recovery keys, and automatic locking after inactivity.
- Quick capture (K), note search and navigation (J), and book titles at the crosshair.
- Automatic backups outside the world save when entering a world.

See the [README](https://github.com/congyuemao/memory-palace#readme) for details.
