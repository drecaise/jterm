@echo off
rem Builds jterm-rdp-helper.exe against a FreeRDP 3 install and stages it with its DLLs.
rem Usage: build-windows.cmd [FREERDP_DIST]   (default: %USERPROFILE%\git\freerdp\dist)
rem Output: build\stage\  (jterm-rdp-helper.exe + FreeRDP/OpenSSL/zlib/MSVC runtime DLLs)
setlocal
set HERE=%~dp0
set HERE=%HERE:~0,-1%
set FREERDP_DIST=%~1
if "%FREERDP_DIST%"=="" set FREERDP_DIST=%USERPROFILE%\git\freerdp\dist
rem FreeRDP's CMake package re-finds its own dependencies (zlib, OpenSSL): point at the vcpkg tree
rem it was built with.
set DEPS=%~2
if "%DEPS%"=="" set DEPS=%FREERDP_DIST%\..\vcpkg\installed\x64-windows

for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set VSPATH=%%i
if not defined VSPATH (echo MSVC not found & exit /b 1)
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1

cmake -S "%HERE%" -B "%HERE%\build" -G Ninja -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_PREFIX_PATH="%FREERDP_DIST%;%DEPS%" -DCMAKE_INSTALL_PREFIX="%HERE%\build\stage" || exit /b 1
cmake --build "%HERE%\build" || exit /b 1
cmake --install "%HERE%\build" || exit /b 1

rem The helper's own directory is the first place Windows looks for its DLLs.
copy /y "%FREERDP_DIST%\bin\*.dll" "%HERE%\build\stage\" >nul
echo HELPER OK: %HERE%\build\stage\jterm-rdp-helper.exe
