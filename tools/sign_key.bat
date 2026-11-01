@echo off
rem 双击：用正式签名密钥打两个 release 包（弹一个打码输入框，输一次口令就完事）。
rem 里面跑的是 tools\sign_release.ps1。口令只进那个进程的内存，不写文件、不进命令行参数。
rem 跑完会自动用 apksigner 回读真实签名、和 E:\dev\keys 里记录的指纹比对，
rem 对不上会直接告诉你"别把这些包发出去"。
title OmniRing 正式签名打包
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "E:\dev\hzr\tools\sign_release.ps1"
