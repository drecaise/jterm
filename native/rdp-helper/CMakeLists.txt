cmake_minimum_required(VERSION 3.16)
project(jterm-rdp-helper C)

set(CMAKE_C_STANDARD 11)

find_package(WinPR 3 REQUIRED)
find_package(FreeRDP 3 REQUIRED)
find_package(FreeRDP-Client 3 REQUIRED)

# AAC decoding through the system's decoder (src/aac.h); never a bundled codec.
# The Flatpak runtime has no libfaad but its own FFmpeg: the manifest sets JTERM_AAC_FFMPEG. Only the
# SDK's headers are used, the library is opened at run time (src/aac_ffmpeg.c).
option(JTERM_AAC_FFMPEG "Decode AAC with the Flatpak runtime's libavcodec" OFF)
if(JTERM_AAC_FFMPEG AND NOT WIN32 AND NOT APPLE)
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(JTERM_FFMPEG REQUIRED libavcodec libavutil)
  set(JTERM_AAC_SOURCE src/aac_ffmpeg.c)
elseif(WIN32)
  set(JTERM_AAC_SOURCE src/aac_mf.c)    # Media Foundation's AAC decoder MFT, part of Windows
elseif(APPLE)
  set(JTERM_AAC_SOURCE src/aac_audiotoolbox.c)  # AudioToolbox's AudioConverter, part of macOS
else()
  set(JTERM_AAC_SOURCE src/aac_faad.c)  # the distribution's libfaad.so.2, by absolute path
endif()
set(JTERM_SOURCES src/jterm_rdp_helper.c src/audio.c src/sandbox.c src/netroute.c src/launcher_win.c ${JTERM_AAC_SOURCE})

if(WIN32)
  # GUI subsystem: a console-subsystem child of javaw.exe would pop up a console window.
  add_executable(jterm-rdp-helper WIN32 ${JTERM_SOURCES})
  if(MSVC)
    target_link_options(jterm-rdp-helper PRIVATE /ENTRY:mainCRTStartup)
    target_compile_options(jterm-rdp-helper PRIVATE /W4)
  endif()
else()
  add_executable(jterm-rdp-helper ${JTERM_SOURCES})
  target_compile_options(jterm-rdp-helper PRIVATE -Wall -Wextra)
  # Find the bundled FreeRDP libraries next to the helper. --disable-new-dtags writes DT_RPATH, which
  # (unlike DT_RUNPATH, the default on e.g. Ubuntu) also applies to the libraries' own dependencies:
  # FreeRDP installs its libraries with RPATH $ORIGIN/../lib, so without it libfreerdp3 could not find
  # libwinpr3 sitting beside it.
  if(APPLE)
    # macOS spells "the directory of this binary" @loader_path; build-macos.sh rewrites the bundled
    # libraries to match.
    set_target_properties(jterm-rdp-helper PROPERTIES INSTALL_RPATH "@loader_path")
  else()
    set_target_properties(jterm-rdp-helper PROPERTIES INSTALL_RPATH "$ORIGIN")
    target_link_options(jterm-rdp-helper PRIVATE "LINKER:--disable-new-dtags")
  endif()
endif()

# Exploit mitigations (sandbox design, layer 1). The helper parses a possibly hostile server's bytes;
# these make a memory-safety bug in it harder to turn into code execution. FreeRDP itself gets the
# same flags from build-freerdp-*.sh, and check-hardening.py verifies the staged result.
if(MSVC)
  # Control Flow Guard, CET shadow-stack compatible; ASLR with a 64-bit address space spelled out
  # (MSVC's defaults today, kept explicit so a toolchain change can't drop them silently).
  target_compile_options(jterm-rdp-helper PRIVATE /guard:cf)
  target_link_options(jterm-rdp-helper PRIVATE /guard:cf /CETCOMPAT /DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT)
elseif(NOT WIN32)
  # -U first: some toolchains (Ubuntu's gcc, the Flatpak SDK's CFLAGS) already define it, and a
  # second definition is a warning.
  target_compile_options(jterm-rdp-helper PRIVATE
    -fstack-protector-strong -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2)
  if(NOT APPLE)
    # A position-independent executable (Apple's toolchain makes one by default), and the GOT
    # read-only once the loader is done (full RELRO, no lazy binding).
    set_target_properties(jterm-rdp-helper PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_compile_options(jterm-rdp-helper PRIVATE -fPIE)
    target_link_options(jterm-rdp-helper PRIVATE -pie "LINKER:-z,relro,-z,now")
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|i.86")
      target_compile_options(jterm-rdp-helper PRIVATE -fcf-protection)
    endif()
  endif()
endif()

target_link_libraries(jterm-rdp-helper PRIVATE freerdp-client freerdp winpr)
if(WIN32)
  target_link_libraries(jterm-rdp-helper PRIVATE mfplat mfuuid ole32 ws2_32)
elseif(APPLE)
  target_link_libraries(jterm-rdp-helper PRIVATE "-framework AudioToolbox" "-framework CoreFoundation")
endif()
if(NOT WIN32)
    # dlopen for the downloaded OpenH264 (preload_openh264); part of libc on glibc 2.34+.
    target_link_libraries(jterm-rdp-helper PRIVATE ${CMAKE_DL_LIBS})
endif()
if(JTERM_AAC_SOURCE STREQUAL "src/aac_ffmpeg.c")
  # Headers only: linking would map libavcodec and its ~80 dependencies into every session.
  target_include_directories(jterm-rdp-helper PRIVATE ${JTERM_FFMPEG_INCLUDE_DIRS})
endif()

install(TARGETS jterm-rdp-helper RUNTIME DESTINATION .)
