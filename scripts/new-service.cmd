@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0new-service.ps1" %*
