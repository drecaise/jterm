@echo off
rem Builds the FreeRDP version pinned in FREERDP_VERSION (shared DLLs, Release, x64) for the RDP
rem helper. Used by CI and for local builds; every target bundles this same FreeRDP.
rem
rem Usage: build-freerdp-windows.cmd <workdir>
rem   <workdir>\src     FreeRDP checkout (cloned if missing)
rem   <workdir>\dist    install prefix: bin\*.dll, include\, lib\  (the helper builds against this)
rem   VCPKG_ROOT        an existing vcpkg; else cloned to <workdir>\vcpkg
rem Keep <workdir> short (e.g. native\rdp-helper\build\freerdp, the default build-windows.cmd uses):
rem vcpkg nests ~100 characters of build tree under it, and MSVC fails past the 260-character
rem MAX_PATH with "C1083: Cannot open compiler generated file: ''".
setlocal
set HERE=%~dp0
set HERE=%HERE:~0,-1%
set WORK=%~1
if "%WORK%"=="" (echo usage: %~nx0 ^<workdir^> & exit /b 2)
if not exist "%WORK%" mkdir "%WORK%"
set /p FREERDP_VERSION=<"%HERE%\FREERDP_VERSION"

for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set VSPATH=%%i
if not defined VSPATH (echo MSVC not found & exit /b 1)
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1

if not exist "%WORK%\src\.git" (
  git clone --depth 1 --branch %FREERDP_VERSION% https://github.com/FreeRDP/FreeRDP.git "%WORK%\src" || exit /b 1
)

rem A full (not shallow) clone: vcpkg.json's builtin-baseline is looked up in its git history.
if not defined VCPKG_ROOT set VCPKG_ROOT=%WORK%\vcpkg
if not exist "%VCPKG_ROOT%\vcpkg.exe" (
  if not exist "%VCPKG_ROOT%\.git" git clone https://github.com/microsoft/vcpkg.git "%VCPKG_ROOT%" || exit /b 1
  call "%VCPKG_ROOT%\bootstrap-vcpkg.bat" -disableMetrics || exit /b 1
)
rem Dependencies (vcpkg.json beside this script) as DLLs, installed into the work dir rather than
rem the shared vcpkg tree.
"%VCPKG_ROOT%\vcpkg.exe" install --triplet x64-windows --x-manifest-root="%HERE%" ^
  --x-install-root="%WORK%\vcpkg_installed" --disable-metrics || exit /b 1
set DEPS=%WORK%\vcpkg_installed\x64-windows

rem Client-only build: no server/shadow/proxy, no SDL/FFmpeg. H.264 comes from Windows' own
rem Media Foundation decoder (hardware-accelerated, patent-licensed by Microsoft).
cmake -S "%WORK%\src" -B "%WORK%\build" -G Ninja ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_INSTALL_PREFIX="%WORK%\dist" ^
  -DCMAKE_PREFIX_PATH="%DEPS%" ^
  -DBUILD_SHARED_LIBS=ON ^
  -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreadedDLL ^
  -DWITH_CLIENT_COMMON=ON -DWITH_CLIENT=OFF -DWITH_CLIENT_WINDOWS=OFF -DWITH_CLIENT_SDL=OFF ^
  -DWITH_SERVER=OFF -DWITH_SHADOW=OFF -DWITH_PROXY=OFF -DWITH_SAMPLE=OFF ^
  -DWITH_FFMPEG=OFF -DWITH_SWSCALE=OFF -DWITH_WEBVIEW=OFF -DWITH_MANPAGES=OFF ^
  -DWITH_MEDIA_FOUNDATION=ON ^
  -DWITH_CJSON_REQUIRED=ON ^
  -DWITH_VERBOSE_WINPR_ASSERT=OFF ^
  -DCHANNEL_URBDRC=OFF -DBUILD_TESTING=OFF ^
  -DWITH_INTERNAL_RC4=ON -DWITH_INTERNAL_MD4=ON -DWITH_INTERNAL_MD5=ON || exit /b 1
cmake --build "%WORK%\build" || exit /b 1
cmake --install "%WORK%\build" || exit /b 1

copy /y "%DEPS%\bin\*.dll" "%WORK%\dist\bin\" >nul
rem Keep FreeRDP's license with the install: CI caches dist\ but not the source tree.
copy /y "%WORK%\src\LICENSE" "%WORK%\dist\FreeRDP-LICENSE.txt" >nul || exit /b 1
echo FREERDP OK: %FREERDP_VERSION% in %WORK%\dist
