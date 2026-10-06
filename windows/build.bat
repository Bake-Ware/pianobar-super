@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "WEBVIEW2_VERSION=1.0.4129.50"
set "SDKROOT=%~dp0sdk"

if not exist "%SDKROOT%" mkdir "%SDKROOT%"
if not exist "%SDKROOT%\build\native\include\WebView2.h" (
  echo Downloading WebView2 SDK %WEBVIEW2_VERSION%...
  curl -sSL -o "%SDKROOT%\webview2.nupkg" "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/%WEBVIEW2_VERSION%/microsoft.web.webview2.%WEBVIEW2_VERSION%.nupkg" || goto :fail
  tar -xf "%SDKROOT%\webview2.nupkg" -C "%SDKROOT%" || goto :fail
)

set "INCLUDE_DIR=%SDKROOT%\build\native\include"
set "STATIC_LIB=%SDKROOT%\build\native\x64\WebView2LoaderStatic.lib"
if not exist "%INCLUDE_DIR%\WebView2.h" goto :fail
if not exist "%STATIC_LIB%" goto :fail
echo WebView2 headers: %INCLUDE_DIR%
echo WebView2 static loader: %STATIC_LIB%

set "VCVARS="
for %%E in (
  "C:\Program Files\Microsoft Visual Studio\18\Enterprise"
  "C:\Program Files\Microsoft Visual Studio\18\Professional"
  "C:\Program Files\Microsoft Visual Studio\18\Community"
  "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools"
  "C:\Program Files\Microsoft Visual Studio\2022\Enterprise"
  "C:\Program Files\Microsoft Visual Studio\2022\Professional"
  "C:\Program Files\Microsoft Visual Studio\2022\Community"
  "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"
) do if exist "%%~E\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%%~E\VC\Auxiliary\Build\vcvars64.bat"
if not defined VCVARS (
  echo Visual Studio C++ build tools not found. Install the "Desktop development with C++" workload.
  exit /b 1
)
call "%VCVARS%" || goto :fail

if not exist build mkdir build
set "RESARG="
set "LINKRES="
rc /nologo /fo build\app.res app.rc >nul 2>nul
if %errorlevel% equ 0 (
  set "RESARG=build\app.res"
) else (
  echo rc.exe not found - embedding manifest via linker instead (no custom icon)
  set "LINKRES=/MANIFEST:EMBED /MANIFESTINPUT:app.manifest"
)

cl /nologo /std:c++17 /EHsc /O2 /MT /W3 /DUNICODE /D_UNICODE /DWIN32 ^
  /I"!INCLUDE_DIR!" ^
  pianobar-desktop.cpp !RESARG! ^
  "!STATIC_LIB!" ^
  /Fe:pianobar-desktop.exe /Fo:build\ ^
  /link /SUBSYSTEM:WINDOWS !LINKRES! user32.lib shell32.lib ole32.lib advapi32.lib gdi32.lib dwmapi.lib oleaut32.lib || goto :fail

echo.
echo Built pianobar-desktop.exe
exit /b 0

:fail
echo Build failed.
exit /b 1
