@echo off
rem 双击就能生成 OmniRing 正式签名密钥（图形窗口版，不需要终端、不需要 Git Bash）。
rem 里面跑的是 tools\make_release_key.ps1：口令在弹窗里输两次，只进内存，
rem 不写文件、不进日志、不出现在命令行参数里；已存在密钥时拒绝覆盖。
title OmniRing 生成正式签名密钥
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "E:\dev\hzr\tools\make_release_key.ps1"
