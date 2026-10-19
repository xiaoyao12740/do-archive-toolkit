# Windows Guide (English, with screenshots notes)

> do Archive Toolkit V5 · Windows 10 / 11. Written for beginners; build/self-test sections for developers included.

## What it does
Extract zip/7z/rar/tar/gz/bz2/xz/lz4 (real format detected from header, even if renamed);
create 7z/zip/tar with optional password (7z can also encrypt filenames) and split volumes;
accepts a single file OR a folder; recursively unpacks nested archives; handles split-volume
groups (`part1.rar`, `z01`, `zip.001`, `7z.001`); masked password entry with a reusable password pool;
flat output; optional delete-after-success (off by default); bilingual UI.

## Which file to use
| File | For whom | Python needed? |
|---|---|---|
| **`do压缩包.exe`** | most users / beginners | ❌ no |
| **`解压.bat` / `压缩.bat` + `do_extract.py`** | who want to read/tweak source | ✅ Python 3.8+ |
| **`do.ps1`** | PowerShell / automation users | ✅ Python 3.8+ |

Portable **7-Zip** and **lz4** are bundled under `tools/`, so normally nothing else to install.
Install WinRAR only if you need to **create** RAR (plain extraction does not need it).

## 30-second start (drag & drop)
- **Extract**: drag one archive OR a whole folder onto **`解压.bat`**; contents appear next to it.
  For an encrypted archive, type the password at the prompt (shown as `*`).
- **Compress**: double-click **`压缩.bat`** (or exe → option 2) and follow prompts for
  format / output / password / split size.

## Interactive menu
Double-click `do压缩包.exe`:
```
1. Extract (file or folder; recursive + nested)
2. Compress (zip/7z/tar; optional password & split volumes)
3. 切换语言 / Switch language
0. Quit
```
You can drag a path into the console window instead of typing it.

## Command line / PowerShell
```bat
do_extract.py x "D:\data" --pwd 123456 --del
do_extract.py c "D:\a.txt" "D:\b" -o "D:\out.7z" -f 7z --pwd secret -v 100m
do_extract.py x "D:\data" --lang en
```
Replace `do_extract.py` with `do压缩包.exe` — identical arguments.
```powershell
.\do.ps1 x "D:\data" -Pwd 123456 -Delete
.\do.ps1 c "D:\a.txt" "D:\b" -Out D:\out.7z -Fmt 7z -Pwd secret -Vol 100m
.\do.ps1          # interactive menu
```
| Arg | Meaning |
|---|---|
| `x` / `c` | extract / compress |
| `--pwd P` | password |
| `--del` | delete originals after success (careful) |
| `--lang zh\|en` | language |
| `-o PATH` | (compress) output file |
| `-f 7z\|zip\|tar` | format (default 7z) |
| `-v 100m / 1g` | split volume size |

If PowerShell blocks the script: `powershell -ExecutionPolicy Bypass -File .\do.ps1`.

## First-time setup (only for bat+py / ps1)
1. Install Python 3.10+ from https://www.python.org/downloads/windows/ and tick **Add Python to PATH**.
2. Verify with `python --version` in cmd.
3. 7-Zip / lz4 are bundled; install WinRAR only for RAR creation.
Lookup order for engines: bundled `tools/` → installed paths → PATH.

## Build the exe yourself
```powershell
cd windows
pip install pyinstaller
pyinstaller --onefile --console --name "do压缩包" do_extract.py
```
Keep the produced exe next to the `tools/` folder (it searches there at runtime).

### Author's verified build environment
- Windows 11 Pro · Python 3.13.16 · PyInstaller 6.22.3
- 7-Zip portable bundled (also verified at `C:\Program Files\7-Zip` and `system32\7z.exe`)
- WinRAR installed under `C:\Program Files\WinRAR`
- Passed: encrypted-7z creation; recursive extraction of an outer zip wrapping an inner
  encrypted 7z; Chinese folder/file names.

## Verify in a clean Windows VM (recommended before sharing)
1. Create a Windows 10/11 VM in VMware / VirtualBox (https://www.virtualbox.org) / Hyper-V
   (Hyper-V: Control Panel → Programs → Turn Windows features on/off → enable Hyper-V → Quick Create).
   Keep it clean: no Python, no 7-Zip.
2. Copy only the `windows/` folder into the VM.
3. Using **only the exe**: open the menu; switch language; create an encrypted 7z; delete the
   source; extract it back with the password and confirm byte-identical content; test a `.lz4`
   and a split `.part1/.part2.rar`; confirm bundled tools handle them.
4. Pass criterion: with just `do压缩包.exe + tools/`, every step works in the clean VM.
(Dragging onto a `.bat` without Python will warn that python is missing — that's why beginners get the exe.)

## FAQ
- **Bat flashes and closes?** Python missing / not on PATH — use the exe.
- **7z.exe not found?** Keep `tools\7-Zip\7z.exe` beside the program, or install 7-Zip.
- **RAR?** Extraction via bundled 7-Zip; RAR creation needs WinRAR (https://www.win-rar.com).
- **Delete originals?** Off by default; runs only after confirmed success. Back up important data anyway.
- **Password privacy?** Console shows `*`; the log masks passwords (first/last char + stars).

## Official downloads
7-Zip https://www.7-zip.org · LZ4 https://lz4.github.io/lz4/ · WinRAR https://www.win-rar.com
· Python https://www.python.org/downloads/windows/ · VirtualBox https://www.virtualbox.org

> Non-commercial open source, personal/educational use only — see `LICENSE`.
