# Android Guide (English)

> do压缩包 v1.4.8 · verified on **Android 14 and 17, Snapdragon and Dimensity** devices.

## Features
Batch extract & compress: AES-256 zip, 7z, rar, tar, gz, bz2, xz, lz4; split volumes
(part1.rar / z01 / zip.001 / 7z.001); recursive nested extraction with flat results
(contents + first-archive folder, no deep nesting); dual password books (encrypt / decrypt)
with copy semantics and SQLite unique de-duplication; 12 themes applied across every page
(purple/night/eye-care/kraft/blue/teal/pink/orange/red/gray/mint/cream) plus custom
background image and font color (night mode dims buttons/text); schedulers
low/medium/high/auto/custom(1–16); targets accept a single file OR a folder; optional
delete-after-success (off by default); detailed + brief log export; notification progress;
5 built-in switchable launcher icons; bilingual UI.

## Install (Android 14–17)
1. Send `android/do压缩包_v1.4.8.apk` to the phone (cable / chat / cloud).
2. Open it; on the "unknown sources" prompt choose **Settings → Allow from this source**, then install.
   (Brands vary: Settings → Apps → Special access → Install unknown apps.)
3. Grant **Files / All files access** when asked (needed for batch file handling).
4. **Update by installing over the old version** — saved passwords and settings are kept;
   do not uninstall first (that wipes app data).
The app requests no network permission and works fully offline.

## Extract
1. "① Select target" → choose one archive or a whole folder.
2. "② Scheduler" → usually **Auto** (low/medium on low-end phones).
3. Optionally tick "delete original archive after success" (off by default).
4. Tap **Start**. For encrypted archives enter the password (you can save it to a password book).
5. Split volumes and nested archives are handled automatically; final content sits in a folder
   named after the first archive.

## Compress
Open the compress page → choose file(s)/folder → encryption (none / partial / full; AES-256 zip)
→ set layers/password/concurrency/output → start.

## Password books (encrypt / decrypt)
- Switch books from the top of the Password page.
- **Adding happens in whichever book is open** — an entry added on the decrypt page goes only there.
- "Move to the other book" actually **copies** (original kept); a light toast confirms
  "copied to …". A password is **unique within a book**; duplicates are rejected.
- Stored locally in Room/SQLite; never uploaded.

## Themes & appearance
Settings → Theme color; one switch recolors extract/compress/settings/password pages together.
Night mode uses a near-black background with dimmed buttons/text and restores correctly when
you switch back (no stuck black buttons). You can also set a custom background color/image and
font color. The top bar uses a theme-tinted frosted effect; a diagonal light-gray watermark is shown.

## App (launcher) icon
Settings → App icon → pick one of 5 presets (default: thinker) → **Save & restart**.
This changes the **home-screen / drawer** icon via Android activity-aliases.
⚠️ Launcher icon refresh is delayed on some systems (notably **Dimensity**); wait a moment,
check and hold → App info, or reboot. Android only allows switching among built-in icons —
an arbitrary photo cannot become the launcher icon (platform limitation).

## Tested
Android 14 and 17 on Snapdragon and Dimensity: batch extract/compress, AES zip, 7z, RAR split
volumes, lz4, multi-level nesting, password-book add/copy/dedup, all 12 themes (including
night-mode round trips), icon switch, log export — all passed; over-install preserved keys/settings.
Developer regression also done on an Android Studio emulator (API 34).

## Build (developers)
The buildable Android Studio (Kotlin + Gradle) project is in `android/source/`. Open it, let
Gradle sync, then Build → Generate Signed APK. Native libarchive/unrar are used over JNI;
provide your own keystore. See `docs/Technical.md`.

## FAQ
- **Passwords gone after update?** You uninstalled first — install over the old app next time.
- **Background job killed?** Allow background run / disable battery optimization / allow auto-start.
- **Launcher icon unchanged?** Launcher cache delay (esp. Dimensity) — wait or reboot.
- **Offline?** Yes. No network permission; everything runs locally.

> Non-commercial open source, personal/educational use only — see `LICENSE`.
