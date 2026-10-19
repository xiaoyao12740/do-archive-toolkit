@echo off
chcp 65001 >nul
title do压缩包 V5 - 压缩 / Compress
cd /d "%~dp0"

REM ============================================================
REM  do压缩包 V5  压缩启动器 / Compression launcher
REM  用法: 1) 双击运行(交互)  2) 把文件/文件夹拖到本图标上
REM        拖拽默认打成 7z，无密码；需要自定义请双击进菜单
REM  Use : double-click (interactive) OR drag file/folder onto it
REM ============================================================

where py >nul 2>nul && (set "PY=py -3") || (set "PY=python")

REM 直接进入交互菜单的“压缩”流程（最稳妥，可设格式/密码/分卷）
%PY% "%~dp0do_extract.py"
pause
