@echo off
setlocal
rem Porneste LocalChat cu Java-ul inclus in folder (runtime\). Daca lipseste, foloseste Java din sistem.
rem Optiunile Java din mediu sunt golite DOAR pentru acest proces (setlocal): runtime-ul inclus are putine module
rem (de ex. fara jdk.crypto.mscapi), iar o valoare mostenita, cum este -Djavax.net.ssl.trustStoreType=Windows-ROOT,
rem strica tot HTTPS-ul (NoSuchAlgorithmException la SSLContext). Variabila poate fi mostenita si de la Explorer.
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
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
