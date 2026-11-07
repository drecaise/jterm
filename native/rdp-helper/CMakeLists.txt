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
  # Find the bundled libfreerdp next to the helper (Linux) / in ../Frameworks-style dirs later.
  set_target_properties(jterm-rdp-helper PROPERTIES INSTALL_RPATH "$ORIGIN")
endif()

target_link_libraries(jterm-rdp-helper PRIVATE freerdp-client freerdp winpr)

install(TARGETS jterm-rdp-helper RUNTIME DESTINATION .)
