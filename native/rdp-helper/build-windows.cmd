@echo off
rem Builds jterm-rdp-helper.exe against a FreeRDP 3 install and stages it with its DLLs.
rem Usage: build-windows.cmd [FREERDP_DIST [DEPS]]
rem   FREERDP_DIST  FreeRDP install prefix (default: build-freerdp-windows.cmd's <workdir>\dist
rem                 at build\freerdp\dist, else %USERPROFILE%\git\freerdp\dist)
rem   DEPS          the vcpkg tree FreeRDP was built with (its CMake package re-finds zlib/OpenSSL)
rem Output: build\stage\  (jterm-rdp-helper.exe + FreeRDP/OpenSSL/zlib/MSVC runtime DLLs)
setlocal
set HERE=%~dp0
set HERE=%HERE:~0,-1%
set FREERDP_DIST=%~1
if "%FREERDP_DIST%"=="" if exist "%HERE%\build\freerdp\dist" set FREERDP_DIST=%HERE%\build\freerdp\dist
if "%FREERDP_DIST%"=="" set FREERDP_DIST=%USERPROFILE%\git\freerdp\dist
set DEPS=%~2
if "%DEPS%"=="" if exist "%FREERDP_DIST%\..\vcpkg_installed\x64-windows" set DEPS=%FREERDP_DIST%\..\vcpkg_installed\x64-windows
if "%DEPS%"=="" set DEPS=%FREERDP_DIST%\..\vcpkg\installed\x64-windows

for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set VSPATH=%%i
if not defined VSPATH (echo MSVC not found & exit /b 1)
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1

cmake -S "%HERE%" -B "%HERE%\build\helper" -G Ninja -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_PREFIX_PATH="%FREERDP_DIST%;%DEPS%" -DCMAKE_INSTALL_PREFIX="%HERE%\build\stage" || exit /b 1
cmake --build "%HERE%\build\helper" || exit /b 1
cmake --install "%HERE%\build\helper" || exit /b 1

rem The helper's own directory is the first place Windows looks for its DLLs. Only FreeRDP's own
rem tools are left out.
copy /y "%FREERDP_DIST%\bin\*.dll" "%HERE%\build\stage\" >nul

rem License texts of everything bundled travel with the binaries (FreeRDP: Apache-2.0; OpenSSL:
rem Apache-2.0; zlib: zlib; cJSON: MIT). The MSVC runtime DLLs are Microsoft redistributables.
if not exist "%HERE%\build\stage\licenses" mkdir "%HERE%\build\stage\licenses"
set FREERDP_LICENSE=%FREERDP_DIST%\FreeRDP-LICENSE.txt
if not exist "%FREERDP_LICENSE%" set FREERDP_LICENSE=%FREERDP_DIST%\..\src\LICENSE
copy /y "%FREERDP_LICENSE%" "%HERE%\build\stage\licenses\FreeRDP-LICENSE.txt" >nul || exit /b 1
for %%p in (openssl zlib cjson) do (
  copy /y "%DEPS%\share\%%p\copyright" "%HERE%\build\stage\licenses\%%p-LICENSE.txt" >nul || exit /b 1
)
echo HELPER OK: %HERE%\build\stage\jterm-rdp-helper.exe
