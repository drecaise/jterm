# Building a native `wfreerdp.exe` for jterm on Windows

jterm's RDP tab embeds FreeRDP's window into the tab (Win32 `SetParent`) and forwards keyboard
focus to it (`AttachThreadInput` + `SetFocus`). For that to work cleanly you need the **native
Win32/GDI** FreeRDP client.

The prebuilt MSYS2 / FreeRDP 3.x packages ship an **SDL-based `wfreerdp`** (it prints
*"wfreerdp has been deprecated, use the SDL client"* and `WITH_CLIENT_SDL2=ON`). SDL manages its
own window, input and keyboard focus, which **fights embedding** — that's why keyboard input and
exact window-fill don't work with it.

The fix is to build **FreeRDP 2.11.x**, whose `wfreerdp` is the mature native GDI client (standard
Win32 windows + input + focus). Its command-line flags are exactly what jterm uses, so nothing
else has to change.

---

## What you'll download / install

| Tool | Why | URL |
|------|-----|-----|
| **Visual Studio 2022 Community** | C++ compiler (MSVC) + CMake + Ninja. Pick the **"Desktop development with C++"** workload. | https://visualstudio.microsoft.com/downloads/ |
| **Git for Windows** | clone FreeRDP + vcpkg | https://git-scm.com/download/win |
| **CMake** (optional — VS bundles one) | configure/build | https://cmake.org/download/ |
| **vcpkg** | builds the dependencies (OpenSSL, zlib) | https://github.com/microsoft/vcpkg |
| **FreeRDP source** | the thing we're compiling | https://github.com/FreeRDP/FreeRDP (tag **2.11.7**) |

Reference docs:
- FreeRDP compilation wiki: https://github.com/FreeRDP/FreeRDP/wiki/Compilation
- FreeRDP 2.x tags/releases: https://github.com/FreeRDP/FreeRDP/tags

> Install Visual Studio **first** and make sure the "Desktop development with C++" workload is
> checked — everything below needs the MSVC toolchain it provides.

---

## Build steps

Open the **"x64 Native Tools Command Prompt for VS 2022"** (Start menu → it sets up the MSVC
environment). Run everything below in that prompt.

### 1. Get and bootstrap vcpkg

```cmd
git clone https://github.com/microsoft/vcpkg C:\vcpkg
cd C:\vcpkg
bootstrap-vcpkg.bat
```

### 2. Build the dependencies

```cmd
C:\vcpkg\vcpkg.exe install openssl:x64-windows zlib:x64-windows
```

(That compiles OpenSSL + zlib as DLLs under `C:\vcpkg\installed\x64-windows`. It takes a while.)

### 3. Get the FreeRDP 2.11.7 source

```cmd
git clone --branch 2.11.7 --depth 1 https://github.com/FreeRDP/FreeRDP C:\src\FreeRDP
cd C:\src\FreeRDP
```

### 4. Configure with CMake

```cmd
cmake -B build -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_TOOLCHAIN_FILE=C:\vcpkg\scripts\buildsystems\vcpkg.cmake ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DWITH_CLIENT=ON ^
  -DWITH_SERVER=OFF ^
  -DWITH_SAMPLE=OFF ^
  -DBUILD_SHARED_LIBS=ON
```

Key flags: `WITH_CLIENT=ON` builds the Windows client (`wfreerdp`); this is the **native GDI**
client in 2.x (no SDL). Server/sample are off to keep it small.

### 5. Build and stage everything into one folder

```cmd
cmake --build build --config Release
cmake --install build --config Release --prefix C:\freerdp-dist
```

The `--install` step copies `wfreerdp.exe` **and FreeRDP's own DLLs** into
`C:\freerdp-dist\bin`.

### 6. Add the OpenSSL + zlib DLLs

`wfreerdp.exe` also needs the vcpkg-built crypto/zlib DLLs next to it:

```cmd
copy C:\vcpkg\installed\x64-windows\bin\libssl-3-x64.dll    C:\freerdp-dist\bin\
copy C:\vcpkg\installed\x64-windows\bin\libcrypto-3-x64.dll C:\freerdp-dist\bin\
copy C:\vcpkg\installed\x64-windows\bin\zlib1.dll           C:\freerdp-dist\bin\
```

(DLL names can vary slightly by version — copy whatever `lib*.dll` and `zlib*.dll` are in that
vcpkg `bin` folder if the exact names above don't exist.)

### 7. Sanity-check it runs standalone

```cmd
C:\freerdp-dist\bin\wfreerdp.exe /version
```

- It should print a **2.11.7** version and **not** any "deprecated / SDL" warning.
- If you get a *"…​.dll was not found"* popup, copy that DLL into `C:\freerdp-dist\bin` (find it
  under `C:\vcpkg\installed\x64-windows\bin` or `C:\src\FreeRDP\build`).

---

## Point jterm at it

Set the override to the **binary** (not the folder), keeping all the DLLs alongside it:

```cmd
set JTERM_FREERDP=C:\freerdp-dist\bin\wfreerdp.exe
%JAVA_HOME%\bin\java.exe -jar jterm.jar
```

To make it permanent (new shells only): `setx JTERM_FREERDP "C:\freerdp-dist\bin\wfreerdp.exe"`.

Or, instead of the env var, copy the whole `C:\freerdp-dist\bin` folder next to `jterm.jar` and
rename it `freerdp` — jterm also looks in `<jar dir>\freerdp\`.

When you reconnect, the startup log should show the command using your new path and **no SDL
warnings**; the `RDP-EMBED(win): SetFocus(child) prevFocus=…` line should report a non-null
previous window once keyboard focus takes.

---

## Troubleshooting

- **DLL-not-found on launch** → a dependency wasn't staged. Run
  `dumpbin /dependents C:\freerdp-dist\bin\wfreerdp.exe` (from the VS prompt) to list what it needs,
  and copy those DLLs in from `C:\vcpkg\installed\x64-windows\bin`.
- **CMake can't find OpenSSL** → confirm step 2 finished and that the
  `-DCMAKE_TOOLCHAIN_FILE=…\vcpkg.cmake` path in step 4 is correct.
- **Still says "deprecated"/SDL** → you built 3.x by mistake. Re-check you cloned the **2.11.7**
  tag in step 3.
- **Want a smaller drop** → after step 6, `C:\freerdp-dist\bin` is self-contained; you can zip just
  that folder and use it on any Windows machine with the same architecture (x64).

---

### Alternative: MSYS2 / MinGW

If you prefer MinGW over Visual Studio, install MSYS2 (https://www.msys2.org/), open the
**MINGW64** shell, install the toolchain + deps
(`pacman -S mingw-w64-x86_64-{toolchain,cmake,openssl,zlib}`), then run the same CMake configure
(drop the vcpkg toolchain line; use `-G Ninja`). Build with `cmake --build build`. This also
produces the native `wfreerdp.exe`, but you must gather the MinGW runtime DLLs
(`libgcc_s_seh-1.dll`, `libwinpthread-1.dll`, `libssl-*.dll`, `libcrypto-*.dll`, `zlib1.dll`, …)
next to it. The Visual Studio path above is usually less fiddly for redistribution.
