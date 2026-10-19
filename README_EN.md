<div align="center">

# 📦 do Archive Toolkit · do压缩包

**A bilingual (中文 / English) batch extractor & archiver — for both Windows and Android**

[![License: Non-Commercial](https://img.shields.io/badge/License-Non--Commercial-red)](./LICENSE)
[![Platform: Windows](https://img.shields.io/badge/Windows-10%20%2F%2011-0078D4?logo=windows&logoColor=white)](./windows)
[![Platform: Android](https://img.shields.io/badge/Android-14%20--%2017-3DDC84?logo=android&logoColor=white)](./android)

</div>

> ⚠️ **Personal & non-commercial use only.** Selling, paid redistribution, or running a
> paid compression/extraction service with this tool is prohibited. See [`LICENSE`](./LICENSE).
> It only invokes the CLIs of **7-Zip / LZ4 / WinRAR** and ships none of their proprietary source.

> 中文文档请看 [`README.md`](./README.md)。

---

## ✨ Features

### 🖥️ Windows (`windows/`, this V5 release)
- **Both extract & compress** — extract zip/7z/rar/tar/gz/bz2/xz/lz4; create 7z/zip/tar with optional password and split volumes
- **File OR folder** — drop a single archive or a whole directory
- **Recursive "nested" extraction** — archives inside extracted results are unpacked automatically
- **Split-volume aware** — `part1.rar`, `z01`, `zip.001`, `7z.001` handled & cleaned as one group
- **Password pool** with masked input; reused automatically across many archives
- **Flat output** — contents land next to the archive, no 20-level folder nesting
- **Optional delete-after-success** (off by default to prevent mistakes)
- **Bilingual UI** — switch 中文 / English instantly
- **Three flavors** — portable `.exe` (no Python needed), `.bat + .py` (readable source), `.ps1`
- Portable **7-Zip** and **lz4** bundled — usually zero configuration

### 📱 Android (`android/`, v1.4.8)
- Batch extract/compress, AES-256 zip & 7z, split volumes, nested archives, dual password books
- 12 themes applied across every page (night/eye-care/custom background & font color)
- Schedulers low / medium / high / auto / custom concurrency
- 5 built-in switchable launcher icons; detailed + brief log export
- **Verified on Android 14 and 17, on Snapdragon and Dimensity devices**

---

## 🚀 Quick start

### Windows
1. Open [`windows/`](./windows).
2. **Extract**: drag a file/folder onto **`解压.bat`**, or double-click **`do压缩包.exe`** → option 1.
3. **Compress**: double-click **`压缩.bat`** (or exe → option 2); follow prompts for format/password/volume.
4. No Python? Use **`do压缩包.exe`** directly.

Full walkthrough (with screenshots), build-from-source, and a Windows-VM test recipe:
[`docs/WINDOWS_Guide_EN.md`](./docs/WINDOWS_Guide_EN.md).

### Android
1. Copy [`android/do压缩包_v1.4.8.apk`](./android/do压缩包_v1.4.8.apk) to your phone and allow "install unknown apps".
2. Updating over an existing install keeps saved passwords and settings.
3. Steps and Android 14–17 permission notes: [`docs/ANDROID_Guide_EN.md`](./docs/ANDROID_Guide_EN.md).

---

## 📂 Layout
```
V5/
├─ README.md / README_EN.md     bilingual overview
├─ LICENSE                      non-commercial license (read this)
├─ windows/                     do压缩包.exe · do_extract.py · 解压.bat · 压缩.bat · do.ps1 · tools/
├─ android/                     do压缩包_v1.4.8.apk · source/ (buildable Android Studio project)
├─ docs/                        guides / build env / test report / third-party links
└─ assets/                      images for the README
```

## 🔧 How it works
- **Windows**: Python 3 stdlib driving 7-Zip, lz4 (and WinRAR if installed) via CLI; detects the real
  format from header magic bytes (extension-independent); the `.exe` is built with PyInstaller.
- **Android**: native Kotlin, libarchive/unrar via JNI, Room(SQLite) password DB, foreground Service.

See [`docs/Technical.md`](./docs/Technical.md).

## 🔗 Official third-party downloads
| Tool | Site | Purpose |
|---|---|---|
| **7-Zip** | https://www.7-zip.org | main engine (free, LGPL/BSD) |
| **LZ4** | https://lz4.github.io/lz4/ · https://github.com/lz4/lz4 | fast `.lz4` |
| **WinRAR** | https://www.win-rar.com | RAR creation/handling (shareware) |

## ⚠️ License
Non-commercial open source — see [`LICENSE`](./LICENSE). **Commercial use is forbidden.**
Back up irreplaceable files before destructive operations; the author is not liable for data loss.
