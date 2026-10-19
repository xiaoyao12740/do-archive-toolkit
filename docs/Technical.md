# Architecture & Implementation (English)

Same content as `实现原理.md`, summarized for English readers.

## Concept
Both platforms implement the same pipeline: **pick target → detect format →
extract/compress → handle split volumes & passwords → recurse into nested archives →
clean up / flatten output → log**.

## Windows (`windows/do_extract.py`)
- Pure **Python 3 standard library** (no pip deps); drives **7z.exe**, **lz4.exe**
  (and optionally **Rar.exe**) via `subprocess`; packaged with **PyInstaller --onefile**.
- **Header-magic detection** (extension-independent): `PK\x03\x04` zip, `Rar!\x1a\x07` rar,
  `7z¼¯'` 7z, `\x04\x22M\x18` lz4, `ustar`@257 tar, `\x1f\x8b` gz, `BZh` bz2, `\xfd7zXZ` xz.
  A `7z t` temp probe is a fallback for disguised archives.
- **Split-volume groups** (`partN.rar`, `.zNN`, `.zip.NNN`, `.7z.NNN`) collapse to one
  entry + all members: extract once, delete as a group.
- **BFS queue** recurses through nested archives; **password pool** reuses passwords across
  jobs; `msvcrt` masked entry; password failure detected from 7z's English error markers.
- Output is extracted to a temp dir beside the archive then moved to the same level (**flat**);
  failures move to `未解压_需要密码/` or `未解压_其他原因/`; full log written.
- Engine lookup: bundled `tools/` → registry → common install dirs → PATH.
- Bilingual via an `I18N` dict, switchable in the menu or with `--lang`.
- The bat/ps1 files are thin launchers; the exe is the same script frozen, searching `tools/`
  next to itself.

## Android (`android/source/`)
- Native **Kotlin**. Extraction over JNI: **libarchive** (`LibArchiveBridge.kt`) for
  zip/7z/tar/gz/bz2/xz/lz4 and **unrar** (`UnrarBridge.kt`) for RAR; `ArchiveEngine.kt`
  orchestrates; `CompressEngine.kt` builds AES-256 zip / 7z with layers and split volumes.
- `ExtractService.kt`: foreground service + notification (current archive / overall progress),
  configurable concurrency.
- `PasswordDatabase.kt`: Room/SQLite, separate encrypt/decrypt books, unique constraint
  (dedup); cross-book move is a **copy**.
- UI: `MainActivity` (extract/compress), `PasswordActivity`, `SettingsActivity`, `MyApp`.
- **Theme engine**: a 6-tuple `(bg, fg, sub, accent, toolbar, card)` per theme; `recolorTheme`
  walks the view tree and unconditionally recolors backgrounds/cards/buttons, caching each
  button's original tint so switching back from night mode restores colors (no stuck black).
- **Schedulers**: low/med/high map to thread/IO levels; Auto derives from CPU cores/RAM;
  Custom clamps to a sane 1–16 range.
- **Launcher icon**: five `activity-alias` entries, one enabled / others disabled via
  `PackageManager.setComponentEnabledSetting` — the official way to switch among **built-in**
  icons. Launcher icons must be compile-time resources, so arbitrary user photos are not
  possible (OS limitation).

## Security & privacy
Passwords stay local (Windows: in-memory only; Android: app-private Room); masked entry and
masked logs; originals are never deleted unless explicitly enabled and only after success;
Android requests no network permission. The software only orchestrates third-party CLIs/native
libs and contains none of their proprietary source.
