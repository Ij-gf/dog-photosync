@echo off
chcp 65001 >nul
title 小狗 · 照片墙
cd /d "%~dp0"
echo 正在启动照片墙，稍后会自动打开浏览器…
echo 关闭这个窗口即停止服务。
echo.
start "" "http://localhost:5173"
node server.mjs
echo.
echo 服务已停止，按任意键关闭窗口。
pause >nul
