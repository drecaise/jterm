#!/usr/bin/env bash
#
# Installs jterm desktop integration for the current user so GNOME (and other
# freedesktop desktops) show the app icon in the dash / overview / Alt-Tab.
#
# It installs:
#   - the icon into the hicolor icon theme (scalable SVG + a 256px PNG)
#   - a .desktop file whose StartupWMClass matches the window's WM_CLASS ("jterm")
#
# Re-run after moving the jar. Requires the project to be built (target/jterm.jar).
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$PROJECT_DIR/target/jterm.jar"
SVG="$PROJECT_DIR/src/main/resources/icons/app.svg"

DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
ICON_THEME="$DATA_HOME/icons/hicolor"
APPS_DIR="$DATA_HOME/applications"

[ -f "$JAR" ] || { echo "ERROR: $JAR not found. Build first: mvn -q package -DskipTests"; exit 1; }

echo "Installing icon..."
install -Dm644 "$SVG" "$ICON_THEME/scalable/apps/jterm.svg"
# Render a 256px PNG from the SVG using the app's own classpath (FlatSVGIcon/jsvg).
RENDER_DIR="$(mktemp -d)"
cat > "$RENDER_DIR/RenderIcon.java" <<'JAVA'
import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
public class RenderIcon {
  public static void main(String[] a) throws Exception {
    int size = Integer.parseInt(a[1]);
    FlatSVGIcon ic = new FlatSVGIcon("icons/app.svg", size, size);
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    var g = img.createGraphics(); ic.paintIcon(null, g, 0, 0); g.dispose();
    ImageIO.write(img, "png", new File(a[0]));
  }
}
JAVA
javac -cp "$JAR" -d "$RENDER_DIR" "$RENDER_DIR/RenderIcon.java"
for sz in 48 64 128 256; do
  out="$ICON_THEME/${sz}x${sz}/apps/jterm.png"
  mkdir -p "$(dirname "$out")"
  java -cp "$RENDER_DIR:$JAR" -Djava.awt.headless=true RenderIcon "$out" "$sz"
done
rm -rf "$RENDER_DIR"

echo "Installing .desktop file..."
mkdir -p "$APPS_DIR"
sed "s|JTERM_JAR_PATH|$JAR|" "$PROJECT_DIR/packaging/jterm.desktop" > "$APPS_DIR/jterm.desktop"
chmod 644 "$APPS_DIR/jterm.desktop"

echo "Updating caches..."
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -f "$ICON_THEME" >/dev/null 2>&1 || true
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS_DIR" >/dev/null 2>&1 || true

echo "Done. Launch jterm (or 'java -jar $JAR'); the dash/Alt-Tab icon should now appear."
