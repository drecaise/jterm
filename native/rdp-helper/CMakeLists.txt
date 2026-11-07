cmake_minimum_required(VERSION 3.16)
project(jterm-rdp-helper C)

set(CMAKE_C_STANDARD 11)

find_package(WinPR 3 REQUIRED)
find_package(FreeRDP 3 REQUIRED)
find_package(FreeRDP-Client 3 REQUIRED)

if(WIN32)
  # GUI subsystem: a console-subsystem child of javaw.exe would pop up a console window.
  add_executable(jterm-rdp-helper WIN32 src/jterm_rdp_helper.c)
  if(MSVC)
    target_link_options(jterm-rdp-helper PRIVATE /ENTRY:mainCRTStartup)
    target_compile_options(jterm-rdp-helper PRIVATE /W4)
  endif()
else()
  add_executable(jterm-rdp-helper src/jterm_rdp_helper.c)
  target_compile_options(jterm-rdp-helper PRIVATE -Wall -Wextra)
  # Find the bundled FreeRDP libraries next to the helper. --disable-new-dtags writes DT_RPATH, which
  # (unlike DT_RUNPATH, the default on e.g. Ubuntu) also applies to the libraries' own dependencies:
  # FreeRDP installs its libraries with RPATH $ORIGIN/../lib, so without it libfreerdp3 could not find
  # libwinpr3 sitting beside it.
  set_target_properties(jterm-rdp-helper PROPERTIES INSTALL_RPATH "$ORIGIN")
  if(NOT APPLE)
    target_link_options(jterm-rdp-helper PRIVATE "LINKER:--disable-new-dtags")
  endif()
endif()

target_link_libraries(jterm-rdp-helper PRIVATE freerdp-client freerdp winpr)

install(TARGETS jterm-rdp-helper RUNTIME DESTINATION .)
