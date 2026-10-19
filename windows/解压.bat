@echo off
chcp 65001 >nul
title do压缩包 V5 - 解压 / Extract
cd /d "%~dp0"

REM ============================================================
REM  do压缩包 V5  解压启动器 / Extraction launcher
REM  用法: 1) 双击运行(交互)  2) 把文件/文件夹拖到本图标上
REM  Use : double-click (interactive) OR drag file/folder onto it
REM ============================================================

REM 优先使用随包 Python 启动器，没有则用系统 python
where py >nul 2>nul && (set "PY=py -3") || (set "PY=python")

REM 拖拽模式：有参数直接解压；无参数进交互菜单
if "%~1"=="" (
    %PY% "%~dp0do_extract.py"
) else (
    %PY% "%~dp0do_extract.py" x %*
)
pause
