#!/bin/sh
# Launcher for the Flatpak build. /app/jre is the JRE installed by the
# org.freedesktop.Sdk.Extension.openjdk21 install.sh step.
exec /app/jre/bin/java -jar /app/share/jterm/jterm.jar "$@"
