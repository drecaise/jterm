#!/bin/sh
# Launcher for the snap build. The JRE is bundled via the openjdk-21-jre
# stage-package; resolve its java under $SNAP. The fat jar's manifest already
# carries Add-Opens: java.desktop/sun.awt.X11, so the WM_CLASS=jterm reflection
# in app.Main works with no extra JVM flag.
JAVA="$(ls "$SNAP"/usr/lib/jvm/java-21-openjdk-*/bin/java 2>/dev/null | head -n1)"
exec "$JAVA" -jar "$SNAP/share/jterm/jterm.jar" "$@"
