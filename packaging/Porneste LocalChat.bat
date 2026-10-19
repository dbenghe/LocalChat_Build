@echo off
rem Porneste LocalChat cu Java-ul inclus in folder (runtime\). Daca lipseste, foloseste Java din sistem.
set "APP=%~dp0"
if exist "%APP%runtime\bin\javaw.exe" (
    start "" "%APP%runtime\bin\javaw.exe" -jar "%APP%LocalChat.jar"
    exit /b 0
)
where javaw >nul 2>nul
if errorlevel 1 (
    echo Nu gasesc Java: lipseste folderul "runtime" de langa LocalChat.jar.
    pause
    exit /b 1
)
start "" javaw -jar "%APP%LocalChat.jar"
