# Windows 端使用教程（中文 · 图解版）

> 适用：do压缩包 V5 · Windows 10 / 11。本教程面向零基础用户，也包含开发者关心的构筑/检验方法。

---

## 一、它能做什么

| 能力 | 说明 |
|---|---|
| 解压 | zip / 7z / rar / tar / gz / bz2 / xz / lz4，自动识别真实格式（改了后缀也认） |
| 压缩 | 打成 7z / zip / tar；7z、zip 可设密码（7z 还能连文件名一起加密）；可分卷 |
| 单文件 / 目录 | 解压、压缩都既能选一个包，也能选整个文件夹 |
| 套娃递归 | 解开一层后里面还有压缩包，会自动继续解到底 |
| 分卷 | `电影.part1.rar/.part2.rar`、`a.z01/a.zip`、`a.7z.001` 整组处理 |
| 密码 | 掩码输入（不明文显示），多个包共享密码自动复用 |
| 删除原件 | 成功后可删原压缩包 / 原文件，**默认关闭**（防误删） |
| 双语 | 中文 / English，菜单里一键切换 |

---

## 二、三种版本怎么选

`windows/` 文件夹里有三种形态，**功能完全一样**，按你的情况选一个：

| 文件 | 适合谁 | 要不要装 Python |
|---|---|---|
| **`do压缩包.exe`** | 大多数人 / 小白，双击就能用 | ❌ 不需要 |
| **`解压.bat` / `压缩.bat` + `do_extract.py`** | 想看源码、想改着玩、装了 Python 的人 | ✅ 需要 Python 3.8+ |
| **`do.ps1`** | 习惯 PowerShell、要写进自动化脚本的人 | ✅ 需要 Python 3.8+ |

> `tools/` 里已自带绿色版 **7-Zip** 和 **lz4**，一般不用再装任何东西。
> 只有当你需要**创建 RAR** 时，才需要本机另装 WinRAR（普通解压不需要）。

---

## 三、小白 30 秒上手（拖拽，推荐）

### 解压
1. 打开 `windows/` 文件夹，能看到 **`解压.bat`**；
2. 用鼠标把要解压的**一个压缩包**，或**一整个文件夹**，直接拖到 `解压.bat` 图标上松手；
3. 黑窗口弹出开始自动解压，内容会出现在压缩包旁边；
4. 遇到加密包，窗口会让你输密码（输入时显示 `*`），回车继续；
5. 看到「全部结束」后，按任意键 / 回车关闭。

```
操作示意：
  [资料.part1.rar]  ──拖拽──▶  解压.bat  ──▶  自动生成  资料\...（内容直接可用）
  [一堆包的文件夹]  ──拖拽──▶  解压.bat  ──▶  批量全部解开，套娃自动继续
```

### 压缩
1. 双击 **`压缩.bat`**（或 `do压缩包.exe` 后选 `2`）；
2. 按提示输入/拖入要打包的文件或文件夹路径；
3. 选格式（直接回车=7z）、输出位置（回车=同目录自动命名）、密码（不要就回车）、分卷大小（不要就回车）；
4. 完成后在你指定的位置得到压缩包。

---

## 四、交互菜单（双击 exe / bat）

双击 `do压缩包.exe` 出现：

```
==============================================
do压缩包  Windows 版 V5
当前语言: 中文
==============================================
1. 解压（单个文件或整个文件夹，自动递归+套娃）
2. 压缩（把文件/文件夹打成 zip/7z/tar，可加密、可分卷）
3. 切换语言 / Switch language
0. 退出
请输入选项:
```

- 选 `3` 立即切换中英文；
- 选 `1` / `2` 后按提示操作；
- 路径可以直接把文件拖进黑窗口，会自动填入，**不用手打**。

---

## 五、命令行 / PowerShell 高级用法

### CMD / bat（核心是 `do_extract.py`，或直接用 exe）
```bat
REM 解压整个目录，带密码，成功后删原包
do_extract.py x "D:\资料" --pwd 123456 --del

REM 压缩多个文件为加密 7z，分卷每卷 100MB
do_extract.py c "D:\a.txt" "D:\b文件夹" -o "D:\out.7z" -f 7z --pwd secret -v 100m

REM 英文界面
do_extract.py x "D:\资料" --lang en
```
用 exe 时把 `do_extract.py` 换成 `do压缩包.exe` 即可，参数完全相同。

### PowerShell（`do.ps1`）
```powershell
# 若被执行策略拦截：powershell -ExecutionPolicy Bypass -File .\do.ps1
.\do.ps1 x "D:\资料" -Pwd 123456 -Delete
.\do.ps1 c "D:\a.txt" "D:\b" -Out D:\out.7z -Fmt 7z -Pwd secret -Vol 100m
.\do.ps1            # 不带参数 = 交互菜单
```

### 参数速查
| 参数 | 含义 |
|---|---|
| `x` / `c` | 解压 / 压缩 |
| `--pwd P` | 解压或压缩密码 |
| `--del` | 成功后删除原压缩包/原文件（谨慎） |
| `--lang zh\|en` | 界面语言 |
| `-o 路径` | （压缩）输出文件 |
| `-f 7z\|zip\|tar` | （压缩）格式，默认 7z |
| `-v 100m / 1g` | （压缩）分卷大小 |

---

## 六、首次环境准备（仅 bat+py / ps1 需要）

### 1) 安装 Python（exe 用户可跳过本节）
1. 打开官网 https://www.python.org/downloads/windows/ 下载 Python 3.10+（3.8 以上均可）；
2. 安装时**务必勾选 `Add Python to PATH`**；
3. 安装后打开 `cmd`，输入 `python --version`，能显示版本号即成功。

### 2) 7-Zip / lz4 / WinRAR
- **7-Zip**：本包 `windows\tools\7-Zip\` 已自带绿色版，无需安装。想正式安装：https://www.7-zip.org
- **lz4**：本包 `windows\tools\lz4\` 已自带。官网 https://lz4.github.io/lz4/
- **WinRAR**（仅创建 RAR 才需要，普通解压不需要）：https://www.win-rar.com

程序查找工具的优先级：**本包 `tools/` → 系统已安装目录 → PATH**。所以默认零配置。

---

## 七、从源码自己构建 exe（开发者）

本仓库的 `do压缩包.exe` 就是这样打出来的，纯标准库、无第三方 Python 依赖：

```powershell
cd windows
pip install pyinstaller
pyinstaller --onefile --console --name "do压缩包" do_extract.py
# 产物在 dist\do压缩包.exe；把它放到 windows\ 根目录（与 tools\ 同级）
```

> 注意：exe 运行时会在**自身所在目录**找 `tools\7-Zip` 与 `tools\lz4`，
> 所以分发时请保持 `do压缩包.exe` 与 `tools` 文件夹在同一级。

### 本机实测构建环境（作者）
- 系统：Windows 11 专业版
- Python：3.13.16
- PyInstaller：6.22.3
- 7-Zip：随包绿色版（亦在 `C:\Program Files\7-Zip`、`C:\Windows\system32\7z.exe` 验证）
- WinRAR：已安装 `C:\Program Files\WinRAR`（Rar.exe / WinRAR.exe）
- 实测：加密 7z 压缩、外层 zip 套内层加密 7z 的递归解压、中文目录/文件名，均通过。

---

## 八、用 Windows 虚拟机干净环境检验（推荐发给别人前自测）

为了确认"别人的电脑没装任何东西也能用"，建议在干净虚拟机里测：

1. **准备虚拟机**：用 VMware Workstation / VirtualBox / Hyper-V 任意一个，
   新建一台 **Windows 10 或 11** 虚拟机（不装 Python、不装 7-Zip，保持干净）；
   - Hyper-V：`控制面板 → 程序 → 启用或关闭 Windows 功能 → 勾选 Hyper-V`，重启后用"快速创建"。
   - VirtualBox：https://www.virtualbox.org （免费）
2. **拷入程序**：把整个 `windows/` 文件夹拖进虚拟机（或共享文件夹 / 只读镜像）。
3. **检验点（只用 exe，不装 Python）**：
   - 双击 `do压缩包.exe`，菜单正常显示；
   - 选 `3` 能切英文；
   - 造一个测试文件夹，选 `2` 打成加密 7z；
   - 删掉原文件夹，再选 `1` 解压该 7z，输入密码，内容完整还原；
   - 放一个 `.lz4`、一个分卷 `.part1.rar/.part2.rar`，确认随包工具能处理；
   - 把文件/文件夹拖到 `解压.bat`——若未装 Python，bat 会提示找不到 python，
     **这正是为什么给小白要发 exe**；纯 exe 不受影响。
4. **结论判据**：干净虚拟机里仅凭 `do压缩包.exe + tools/` 即可完成上述全部操作 = 通过。

---

## 九、常见问题 FAQ

**Q1：双击 bat 一闪而过？**
A：多半是没装 Python 或没勾选 Add to PATH。直接改用 `do压缩包.exe`，不需要 Python。

**Q2：提示找不到 7z.exe？**
A：确认 `tools\7-Zip\7z.exe` 与程序在同一目录；或正式安装一次 7-Zip。

**Q3：.rar 能解吗？能压成 rar 吗？**
A：解压 rar 由随包 7-Zip 负责；**创建 rar** 需本机安装 WinRAR（官网见上）。

**Q4：解压后多了一堆同名嵌套文件夹？**
A：V5 默认把内容平铺到包旁边，不再层层嵌套；如仍异常请把日志 `do_extract_log.txt` 反馈。

**Q5："成功后删除原文件"安全吗？**
A：默认是**关**的。只有你明确选 y 才会删，且只在该包确认解压/压缩成功后执行。重要文件仍建议先备份。

**Q6：密码会被记录吗？**
A：屏幕输入只显示 `*`；日志里密码做了打码（首末位 + 星号），不写明文。

---

## 十、官方下载链接汇总
- 7-Zip：https://www.7-zip.org
- LZ4：https://lz4.github.io/lz4/ ｜ 源码 https://github.com/lz4/lz4
- WinRAR：https://www.win-rar.com
- Python：https://www.python.org/downloads/windows/
- VirtualBox：https://www.virtualbox.org

> 再次提醒：本软件**非商用开源**，仅供个人学习与自用，详见根目录 `LICENSE`。
