# -*- coding: utf-8 -*-
"""
================================================================
 do压缩包  Windows 版  V5   (do Archive Toolkit for Windows)
----------------------------------------------------------------
 双语（中文 / English）· 解压 + 压缩 · 单文件或文件夹均可
 Bilingual (zh/en). Extract & compress. File or folder both OK.

 支持格式 / Formats:
   解压 extract : zip / 7z / rar / tar / gz / bz2 / xz / lz4 (含分卷 / incl. split volumes)
   压缩 compress: zip / 7z / tar (+gz/bz2/xz)，可设密码 (zip/7z)、可选分卷

 三种用法 / 3 ways:
   1) 双击 解压.bat / 压缩.bat / do压缩包.exe  -> 交互菜单 interactive menu
   2) 把文件/文件夹拖到 bat 上                    -> 拖拽 drag & drop
   3) 命令行 command line:
        do_extract.py x  <文件/目录> [--pwd P] [--del] [--lang en]
        do_extract.py c  <文件/目录...> -o out.7z [-f 7z|zip|tar] [--pwd P] [-v 100m] [--del]

 非商用开源 / Non-commercial open source. 详见仓库 LICENSE 与 README。
 本程序只是 7-Zip / lz4 / WinRAR 的命令行调用封装，不包含其专有代码。
 It only wraps the CLI of 7-Zip / lz4 / WinRAR and ships no proprietary code of them.
================================================================
"""

import os
import sys
import re
import shutil
import subprocess
import tempfile
import datetime
import getpass
import argparse
from pathlib import Path
from collections import deque

# =====================================================
# 多语言 i18n
# =====================================================

I18N = {
    "zh": {
        "title": "do压缩包  Windows 版 V5",
        "menu_title": "请选择操作",
        "m_extract": "1. 解压（单个文件或整个文件夹，自动递归+套娃）",
        "m_compress": "2. 压缩（把文件/文件夹打成 zip/7z/tar，可加密、可分卷）",
        "m_lang": "3. 切换语言 / Switch language",
        "m_quit": "0. 退出",
        "choose": "请输入选项: ",
        "ask_target": "请输入或拖入【文件或文件夹】路径（多个用分号 ; 分隔）: ",
        "ask_pwd": "请输入解压密码（没有直接回车，可稍后再补）: ",
        "ask_del": "成功后删除原压缩包/原文件吗？(y/N): ",
        "ask_out": "请输入输出压缩包完整路径（如 D:\\out\\资料.7z），回车=同目录自动命名: ",
        "ask_fmt": "压缩格式 7z / zip / tar（回车默认 7z）: ",
        "ask_cpwd": "请输入压缩密码（不要密码直接回车）: ",
        "ask_vol": "分卷大小（如 100m / 1g，回车=不分卷）: ",
        "bye": "再见！",
        "no_7z": "[错误] 未找到 7z.exe。请安装 7-Zip，或把绿色版放到 tools\\7-Zip\\。",
        "no_lz4": "[警告] 未找到 lz4.exe，.lz4 文件将无法处理。",
        "bad_target": "[错误] 路径不存在: ",
        "press_enter": "按回车键退出...",
        "scanning": "正在扫描...",
        "found_files": "发现文件数: ",
        "tasks": "压缩包任务数: ",
        "extracting": "正在解压: ",
        "compress_ok": "压缩完成: ",
        "success_n": "成功解压: ",
        "pwd_fail_n": "需密码未解压: ",
        "other_fail_n": "其他失败: ",
        "done": "全部结束",
        "retry_pwd": "上述包需要密码。输入密码重试；直接回车放弃；输入 q 退出: ",
        "lang_now": "当前语言: 中文",
        "delete_yes": "将在成功后删除原文件",
    },
    "en": {
        "title": "do Archive Toolkit for Windows  V5",
        "menu_title": "Choose an action",
        "m_extract": "1. Extract (a file or a whole folder; recursive + nested)",
        "m_compress": "2. Compress (zip/7z/tar; optional password & split volumes)",
        "m_lang": "3. 切换语言 / Switch language",
        "m_quit": "0. Quit",
        "choose": "Enter choice: ",
        "ask_target": "Enter/drag a FILE or FOLDER path (use ; for multiple): ",
        "ask_pwd": "Password for extraction (Enter for none, can add later): ",
        "ask_del": "Delete original archive/file after success? (y/N): ",
        "ask_out": "Output archive full path (e.g. D:\\out\\data.7z), Enter=auto: ",
        "ask_fmt": "Format 7z / zip / tar (Enter default 7z): ",
        "ask_cpwd": "Archive password (Enter for none): ",
        "ask_vol": "Split volume size (e.g. 100m / 1g, Enter=single file): ",
        "bye": "Bye!",
        "no_7z": "[ERROR] 7z.exe not found. Install 7-Zip or put the portable build in tools\\7-Zip\\.",
        "no_lz4": "[WARN] lz4.exe not found; .lz4 files cannot be handled.",
        "bad_target": "[ERROR] path not found: ",
        "press_enter": "Press Enter to exit...",
        "scanning": "Scanning...",
        "found_files": "Files found: ",
        "tasks": "Archive tasks: ",
        "extracting": "Extracting: ",
        "compress_ok": "Compressed: ",
        "success_n": "Extracted OK: ",
        "pwd_fail_n": "Need password: ",
        "other_fail_n": "Other failed: ",
        "done": "All done",
        "retry_pwd": "These need a password. Type one to retry; Enter to skip; q to quit: ",
        "lang_now": "Language: English",
        "delete_yes": "Originals will be deleted after success",
    },
}

LANG = "zh"
def T(k):
    return I18N[LANG].get(k, k)

# =====================================================
# Paths & bundled tools
# =====================================================
if getattr(sys, "frozen", False):
    APP_DIR = Path(sys.executable).resolve().parent
else:
    APP_DIR = Path(__file__).resolve().parent

BUNDLED_7Z = APP_DIR / "tools" / "7-Zip" / "7z.exe"
BUNDLED_LZ4 = APP_DIR / "tools" / "lz4" / "lz4.exe"

SEVEN_ZIP_CANDIDATES = [
    str(BUNDLED_7Z),
    r"C:\Program Files\7-Zip\7z.exe",
    r"C:\Program Files (x86)\7-Zip\7z.exe",
    r"D:\Program Files\7-Zip\7z.exe",
    r"D:\Program Files (x86)\7-Zip\7z.exe",
    r"C:\Windows\system32\7z.exe",
]
LZ4_CANDIDATES = [
    str(BUNDLED_LZ4),
    r"C:\Program Files\lz4\lz4.exe",
]

LOG_FILE = APP_DIR / "do_extract_log.txt"
PASSWORD_FAIL_DIR = "未解压_需要密码"
OTHER_FAIL_DIR = "未解压_其他原因"

ARCHIVE_SUFFIXES = (".zip", ".rar", ".7z", ".tar", ".lz4", ".gz", ".bz2", ".xz", ".tgz", ".tbz2", ".txz")

STATUS_SUCCESS = "success"
STATUS_PASSWORD = "password"
STATUS_OTHER = "other"
STATUS_NOT_ARCHIVE = "not_archive"

PASSWORD_LIST = []
success_count = 0
password_fail_count = 0
other_fail_count = 0
DELETE_AFTER = False  # 成功后是否删除原压缩包/原文件


# ---------------- tool detection ----------------
def find_tool(candidates):
    for p in candidates:
        if p and os.path.exists(p):
            return p
    w = shutil.which("7z")
    return w if w else None

def find_lz4():
    for p in LZ4_CANDIDATES:
        if p and os.path.exists(p):
            return p
    return shutil.which("lz4")

SEVEN_ZIP = find_tool(SEVEN_ZIP_CANDIDATES)
LZ4_EXE = find_lz4()


def log(msg):
    line = f"[{datetime.datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    print(line)
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass

def mask_pwd(p):
    if not p:
        return "(empty)"
    if len(p) <= 2:
        return "*" * len(p)
    return p[0] + "*" * (len(p) - 2) + p[-1]


# =====================================================
# Format detection (header, not extension)
# =====================================================
def detect_header(path):
    try:
        with open(path, "rb") as f:
            data = f.read(512)
    except Exception:
        return None
    h = data[:32]
    if h.startswith(b"PK\x03\x04"):
        return "zip"
    if h.startswith(b"Rar!\x1a\x07"):
        return "rar"
    if h.startswith(b"7z\xbc\xaf\x27\x1c"):
        return "7z"
    if h.startswith(b"\x04\x22\x4d\x18") or h.startswith(b"\x02\x21\x4c\x18"):
        return "lz4"
    if len(data) >= 262 and data[257:262] == b"ustar":
        return "tar"
    if h[:2] == b"\x1f\x8b":
        return "gz"
    if h.startswith(b"BZh"):
        return "bz2"
    if h.startswith(b"\xfd7zXZ"):
        return "xz"
    return None

def get_volume_info(path):
    name = path.name
    m = re.match(r"^(.*)\.part(\d+)\.rar$", name, re.I)
    if m:
        return {"type": "rar", "base": m.group(1), "index": int(m.group(2))}
    m = re.match(r"^(.*)\.z(\d\d)$", name, re.I)
    if m:
        return {"type": "zip", "base": m.group(1), "index": int(m.group(2))}
    m = re.match(r"^(.*)\.(zip|7z)\.(\d+)$", name, re.I)
    if m:
        return {"type": m.group(2).lower(), "base": m.group(1), "index": int(m.group(3))}
    return None


class ArchiveGroup:
    def __init__(self, path, entry, members, atype):
        self.path = path
        self.entry = entry
        self.members = members
        self.type = atype


def build_volume_group(path):
    info = get_volume_info(path)
    folder = path.parent
    if info:
        members, entry = [], None
        if info["type"] == "rar":
            members = list(folder.glob(info["base"] + ".part*.rar"))
            for f in members:
                x = get_volume_info(f)
                if x and x["index"] == 1:
                    entry = f
                    break
        elif info["type"] == "zip":
            for pat in (info["base"] + ".zip", info["base"] + ".zip.*"):
                for f in folder.glob(pat):
                    if f not in members:
                        members.append(f)
            for f in folder.glob(info["base"] + ".z*"):
                if f.suffix.lower() != ".zip" and re.match(r"\.z\d+$", f.suffix, re.I):
                    members.append(f)
            def zidx(f):
                vi = get_volume_info(f)
                return vi["index"] if vi else 0
            if members:
                entry = min(members, key=zidx)
        elif info["type"] == "7z":
            members = list(folder.glob(info["base"] + ".7z.*"))
            for f in members:
                x = get_volume_info(f)
                if x and x["index"] == 1:
                    entry = f
                    break
        if not entry:
            return None
        members = list(dict.fromkeys(members))
        return ArchiveGroup(path, entry, members, info["type"])

    if path.suffix.lower() == ".zip":
        base = path.stem
        zparts = [f for f in folder.glob(base + ".z*") if re.match(r"\.z\d+$", f.suffix, re.I)]
        if zparts:
            members = list(dict.fromkeys([path] + zparts))
            return ArchiveGroup(path, path, members, "zip")

    return ArchiveGroup(path, path, [path], detect_header(path))


# =====================================================
# Extraction core
# =====================================================
def run_7z_extract(archive, output, password=None):
    cmd = [SEVEN_ZIP, "x", str(archive), f"-o{output}", "-y"]
    cmd.append(f"-p{password}" if password is not None else "-p")
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)

def is_password_error(raw):
    out = raw.decode("utf-8", errors="ignore").lower()
    return any(m in out for m in ("wrong password", "cannot open encrypted archive",
                                  "password required", "data error in encrypted",
                                  "encrypted archive", "enter password"))

def clean_group(group):
    if not DELETE_AFTER:
        return
    n = 0
    for f in group.members:
        try:
            if f.exists():
                f.unlink(); n += 1
        except Exception as e:
            log(f"delete fail {f.name}: {e}")
    log(f"deleted {n}/{len(group.members)}")

def move_results(source, target):
    out = []
    for item in source.iterdir():
        dst = target / item.name
        if dst.exists():
            if dst.is_dir():
                shutil.copytree(item, dst, dirs_exist_ok=True)
                shutil.rmtree(item)
            else:
                dst.unlink(); shutil.move(str(item), str(dst))
        else:
            shutil.move(str(item), str(dst))
        out.append(dst)
    return out

def move_to_folder(path, folder_name, reason):
    if not path.exists():
        return
    g = build_volume_group(path)
    members = g.members if g else [path]
    dest = path.parent / folder_name
    dest.mkdir(exist_ok=True)
    for m in dict.fromkeys(members):
        if not m.exists():
            continue
        dst = dest / m.name
        n = 1
        while dst.exists():
            dst = dest / f"{m.stem}_{n}{m.suffix}"; n += 1
        try:
            shutil.move(str(m), str(dst))
        except Exception:
            pass

def process_lz4(group):
    entry = group.entry
    if not LZ4_EXE:
        log("lz4.exe missing -> cannot handle .lz4")
        return [], STATUS_OTHER
    tmp = Path(tempfile.mkdtemp(prefix="lz4_", dir=entry.parent))
    try:
        out_file = tmp / (entry.stem or "output")
        r = subprocess.run([LZ4_EXE, "-d", "-f", "-q", str(entry), str(out_file)],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)
        if r.returncode != 0:
            return [], STATUS_OTHER
        if not out_file.exists():
            items = [f for f in tmp.iterdir() if f.is_file()]
            if not items:
                return [], STATUS_OTHER
            out_file = items[0]
        dst = entry.parent / out_file.name
        n = 1
        while dst.exists():
            dst = entry.parent / f"{out_file.stem}_{n}{out_file.suffix}"; n += 1
        shutil.move(str(out_file), str(dst))
        clean_group(group)
        return [dst], STATUS_SUCCESS
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def process_one(path, password=None):
    g = build_volume_group(path)
    if not g:
        return [], STATUS_OTHER
    atype = g.type or detect_header(g.entry)
    if atype is None:
        suffix = path.suffix.lower()
        if suffix in ARCHIVE_SUFFIXES or suffix in (".gz", ".bz2", ".xz"):
            # 7z 兜底处理 tar.gz 等
            atype = "sevenzip"
        else:
            return [], STATUS_NOT_ARCHIVE
    if atype == "lz4":
        return process_lz4(g)

    attempts = [password] if password is not None else [None] + list(PASSWORD_LIST)
    log(T("extracting") + g.entry.name + f"  [{atype}]")
    tmp = Path(tempfile.mkdtemp(prefix="extract_", dir=g.entry.parent))
    allout = b""
    try:
        for pwd in attempts:
            if pwd is not None:
                log("try pwd " + mask_pwd(pwd))
            r = run_7z_extract(g.entry, tmp, password=pwd)
            allout += r.stdout + r.stderr
            if r.returncode == 0:
                clean_group(g)
                return move_results(tmp, g.entry.parent), STATUS_SUCCESS
        if is_password_error(allout):
            return [], STATUS_PASSWORD
        return [], STATUS_OTHER
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def is_archive_file(path):
    if not path.is_file():
        return False
    if detect_header(path) is not None:
        return True
    return path.suffix.lower() in ARCHIVE_SUFFIXES

def is_candidate(path):
    if not path.is_file():
        return False
    return path.suffix.lower() in ARCHIVE_SUFFIXES or get_volume_info(path) or detect_header(path) is not None

def collect_files(inputs):
    res = []
    for item in inputs:
        p = Path(item.strip().strip('"'))
        if p.is_file():
            res.append(p)
        elif p.is_dir():
            res += [f for f in p.rglob("*") if f.is_file()]
    return res

def build_tasks(files):
    tasks, used, skipped = [], set(), 0
    for f in files:
        if str(f) in used:
            continue
        if not is_candidate(f):
            skipped += 1; continue
        info = get_volume_info(f)
        if info:
            g = build_volume_group(f)
            if g and str(g.entry) not in used:
                tasks.append(g.entry)
                for m in g.members:
                    used.add(str(m))
            continue
        tasks.append(f); used.add(str(f))
    return tasks, skipped

# ---------------- password prompt (Windows masked) ----------------
try:
    import msvcrt
except ImportError:
    msvcrt = None

def read_password(prompt):
    if os.name != "nt" or msvcrt is None:
        try:
            return getpass.getpass(prompt)
        except Exception:
            return input(prompt)
    sys.stdout.write(prompt); sys.stdout.flush()
    chars = []
    while True:
        ch = msvcrt.getwch()
        if ch in ("\r", "\n"):
            break
        if ch in ("\x08", "\x7f"):
            if chars: chars.pop()
        elif ch == "\x03":
            sys.stdout.write("\n"); return ""
        elif ch == "\x1b":
            chars.clear()
        elif ch in ("\x00", "\xe0"):
            msvcrt.getwch()
        else:
            chars.append(ch)
        sys.stdout.write("\r" + prompt + "*" * len(chars) + " \b")
        sys.stdout.flush()
    sys.stdout.write("\n")
    return "".join(chars)

def handle_pending(pending, queue):
    global success_count, password_fail_count, other_fail_count
    while pending:
        ans = read_password(T("retry_pwd")).strip()
        if ans.lower() in ("q", "exit"):
            for p in pending:
                move_to_folder(p, PASSWORD_FAIL_DIR, "user quit")
                password_fail_count += 1
            pending.clear(); sys.exit(0)
        if ans == "":
            for p in pending:
                move_to_folder(p, PASSWORD_FAIL_DIR, "no password")
                password_fail_count += 1
            pending.clear(); return
        if ans not in PASSWORD_LIST:
            PASSWORD_LIST.append(ans)
        still = []
        for p in pending:
            results, status = process_one(p, password=ans)
            if status == STATUS_SUCCESS:
                success_count += 1
                for f in results:
                    if f.exists() and is_archive_file(f):
                        queue.append(f)
            elif status == STATUS_PASSWORD:
                still.append(p)
            else:
                move_to_folder(p, OTHER_FAIL_DIR, "fail after pwd")
                other_fail_count += 1
        pending[:] = still

def run_extract(targets, init_pwd=None):
    global success_count, other_fail_count
    if init_pwd:
        PASSWORD_LIST.append(init_pwd)
    log(T("scanning"))
    files = collect_files(targets)
    log(T("found_files") + str(len(files)))
    tasks, skipped = build_tasks(files)
    log(T("tasks") + str(tasks) if False else T("tasks") + str(len(tasks)))
    queue = deque(tasks)
    pending = []
    while queue:
        cur = queue.popleft()
        if not cur.exists():
            continue
        results, status = process_one(cur)
        if status == STATUS_SUCCESS:
            success_count += 1
            for f in results:
                if f.exists() and is_archive_file(f):
                    queue.append(f)
        elif status == STATUS_PASSWORD:
            pending.append(cur)
            handle_pending(pending, queue)
        elif status == STATUS_OTHER:
            other_fail_count += 1
            move_to_folder(cur, OTHER_FAIL_DIR, "extract fail")


# =====================================================
# Compression core
# =====================================================
def run_compress(sources, out_path, fmt="7z", password=None, volume=None, del_after=False):
    """sources: list of file/folder Path; out_path: Path to archive."""
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "tar":
        # 用 7z 的 tar；先打 tar，再按需要 gz/bz2/xz（此处仅纯 tar，简单稳定）
        cmd = [SEVEN_ZIP, "a", "-ttar", str(out_path)] + [str(s) for s in sources]
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)
    else:
        t = "7z" if fmt == "7z" else "zip"
        cmd = [SEVEN_ZIP, "a", f"-t{t}", "-y", str(out_path)]
        if password:
            cmd.append(f"-p{password}")
            if fmt == "7z":
                cmd.append("-mhe=on")  # 7z 加密文件名
        if volume:
            cmd.append(f"-v{volume}")
        cmd += [str(s) for s in sources]
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)
    ok = (r.returncode == 0)
    if ok:
        log(T("compress_ok") + str(out_path))
        if del_after:
            for s in sources:
                try:
                    if s.is_dir():
                        shutil.rmtree(s)
                    else:
                        s.unlink()
                except Exception as e:
                    log(f"delete source fail {s}: {e}")
    else:
        sys.stdout.write(r.stdout.decode("utf-8", "ignore"))
        sys.stdout.write(r.stderr.decode("utf-8", "ignore"))
    return ok


def auto_output(sources, fmt):
    base = Path(sources[0])
    stem = base.stem if base.is_file() else base.name
    parent = base.parent if base.is_file() else base.parent
    name = stem + "." + fmt
    out = parent / name
    n = 1
    while out.exists():
        out = parent / f"{stem}_{n}.{fmt}"; n += 1
    return out


# =====================================================
# Interactive / parsing
# =====================================================
def split_paths(text):
    # 支持分号分隔；去引号空白
    return [x for x in re.split(r"[;；]", text) if x.strip()]

def ask_yes_no(prompt, default=False):
    a = input(prompt).strip().lower()
    if not a:
        return default
    return a in ("y", "yes", "是", "1")

def interactive():
    global DELETE_AFTER, LANG
    while True:
        print("\n" + "=" * 46)
        print(T("title"))
        print(T("lang_now"))
        print("=" * 46)
        print(T("m_extract"))
        print(T("m_compress"))
        print(T("m_lang"))
        print(T("m_quit"))
        c = input(T("choose")).strip()
        if c == "0":
            print(T("bye")); break
        if c == "3":
            LANG = "en" if LANG == "zh" else "zh"
            continue
        if c == "1":
            raw = input(T("ask_target"))
            targets = split_paths(raw)
            targets = [t for t in targets if Path(t.strip().strip('"')).exists()]
            if not targets:
                print(T("bad_target") + raw); continue
            pwd = read_password(T("ask_pwd")).strip() or None
            DELETE_AFTER = ask_yes_no(T("ask_del"))
            if DELETE_AFTER:
                log(T("delete_yes"))
            run_extract(targets, init_pwd=pwd)
            _summary()
        elif c == "2":
            raw = input(T("ask_target"))
            srcs = [Path(x.strip().strip('"')) for x in split_paths(raw) if Path(x.strip().strip('"')).exists()]
            if not srcs:
                print(T("bad_target") + raw); continue
            fmt = (input(T("ask_fmt")).strip().lower() or "7z")
            if fmt not in ("7z", "zip", "tar"):
                fmt = "7z"
            out_raw = input(T("ask_out")).strip().strip('"')
            outp = Path(out_raw) if out_raw else auto_output(srcs, fmt)
            cpwd = read_password(T("ask_cpwd")).strip() or None
            vol = input(T("ask_vol")).strip().lower() or None
            da = ask_yes_no(T("ask_del"))
            run_compress(srcs, outp, fmt=fmt, password=cpwd, volume=vol, del_after=da)
            _summary()
        else:
            continue
        input(T("press_enter"))

def _summary():
    print("-" * 40)
    print(T("done"))
    print(T("success_n") + str(success_count))
    print(T("pwd_fail_n") + str(password_fail_count))
    print(T("other_fail_n") + str(other_fail_count))
    print("-" * 40)


def main():
    global DELETE_AFTER, LANG
    try:
        open(LOG_FILE, "w", encoding="utf-8").write("do archive V5 log\n")
    except Exception:
        pass

    if not SEVEN_ZIP:
        print(T("no_7z"))
        input(T("press_enter")); return
    if not LZ4_EXE:
        print(T("no_lz4"))

    # 命令行参数
    if len(sys.argv) > 1 and sys.argv[1] in ("x", "c"):
        ap = argparse.ArgumentParser()
        ap.add_argument("mode", choices=["x", "c"])
        ap.add_argument("targets", nargs="+")
        ap.add_argument("--pwd", default=None)
        ap.add_argument("--del", dest="delete_after", action="store_true")
        ap.add_argument("--lang", default="zh", choices=["zh", "en"])
        ap.add_argument("-o", "--out", default=None)
        ap.add_argument("-f", "--fmt", default="7z")
        ap.add_argument("-v", "--volume", default=None)
        a = ap.parse_args()
        LANG = a.lang
        DELETE_AFTER = a.delete_after
        if a.mode == "x":
            run_extract(a.targets, init_pwd=a.pwd)
            _summary()
        else:
            srcs = [Path(t) for t in a.targets if Path(t).exists()]
            outp = Path(a.out) if a.out else auto_output(srcs, a.fmt)
            ok = run_compress(srcs, outp, fmt=a.fmt, password=a.pwd, volume=a.volume, del_after=a.delete_after)
            sys.exit(0 if ok else 1)
        return

    # 拖拽：bat 把 %* 传进来，首参数不是 x/c 时视为解压目标
    if len(sys.argv) > 1:
        targets = [t for t in sys.argv[1:] if Path(t).exists()]
        if targets:
            run_extract(targets)
            _summary()
            input(T("press_enter"))
            return

    interactive()


if __name__ == "__main__":
    main()
