/*
 * Click-to-zoom for Mermaid diagrams.
 *
 * Material renders each ```mermaid fence into a <div class="mermaid"> whose <svg> lives
 * in a *closed* shadow root (so the SVG can't be read or cloned from outside). Clicking a
 * diagram therefore MOVES the live host node into a full-screen overlay (its shadow tree
 * travels with it), scales it to fit, and restores it to its original spot on close.
 * Wheel zooms, drag pans. A single delegated listener on `document` (which persists across
 * Material's navigation.instant page swaps) drives everything, attached exactly once.
 * Clicks inside the closed shadow root retarget to the host div, so detection still works.
 */
(function () {
  if (window.__mermaidZoomInstalled) return;
  window.__mermaidZoomInstalled = true;

  var overlay, stage, svgHolder, closeBtn;
  var host = null, placeholder = null, savedCss = "";
  var scale = 1, tx = 0, ty = 0;
  var dragging = false, startX = 0, startY = 0;

  function applyTransform() {
    svgHolder.style.transform =
      "translate(" + tx + "px," + ty + "px) scale(" + scale + ")";
  }

  function buildOverlay() {
    overlay = document.createElement("div");
    overlay.className = "mermaid-zoom-overlay";

    stage = document.createElement("div");
    stage.className = "mermaid-zoom-stage";

    svgHolder = document.createElement("div");
    svgHolder.className = "mermaid-zoom-svg";

    closeBtn = document.createElement("button");
    closeBtn.className = "mermaid-zoom-close";
    closeBtn.setAttribute("aria-label", "Close diagram");
    closeBtn.innerHTML = "&times;";

    stage.appendChild(svgHolder);
    overlay.appendChild(stage);
    overlay.appendChild(closeBtn);

    // Backdrop (but not the diagram) closes on click.
    overlay.addEventListener("click", function (e) {
      if (e.target === overlay || e.target === stage) close();
    });
    closeBtn.addEventListener("click", close);

    stage.addEventListener("wheel", function (e) {
      e.preventDefault();
      var factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
      scale = Math.min(20, Math.max(0.2, scale * factor));
      applyTransform();
    }, { passive: false });

    svgHolder.addEventListener("mousedown", function (e) {
      dragging = true;
      startX = e.clientX - tx;
      startY = e.clientY - ty;
      svgHolder.classList.add("dragging");
      e.preventDefault();
    });
    window.addEventListener("mousemove", function (e) {
      if (!dragging) return;
      tx = e.clientX - startX;
      ty = e.clientY - startY;
      applyTransform();
    });
    window.addEventListener("mouseup", function () {
      dragging = false;
      if (svgHolder) svgHolder.classList.remove("dragging");
    });
  }

  function open(node) {
    if (host) return; // already showing one
    if (!overlay || !document.body.contains(overlay)) buildOverlay();

    host = node;
    savedCss = host.getAttribute("style") || "";
    // Shrink the block host to the SVG's natural size so the fit math is accurate.
    host.style.cssText +=
      ";display:inline-block;width:max-content;max-width:none;margin:0;";

    // Remember where it was, then move the live node into the overlay.
    placeholder = document.createElement("span");
    placeholder.style.display = "none";
    host.parentNode.insertBefore(placeholder, host);
    svgHolder.appendChild(host);

    scale = 1; tx = 0; ty = 0; applyTransform();

    document.body.appendChild(overlay);
    document.body.classList.add("mermaid-zoom-open");
    void overlay.offsetWidth; // reflow so the fade-in runs
    overlay.classList.add("visible");

    // Scale to fill most of the viewport (enlarges small diagrams; zoom-out available).
    var rect = host.getBoundingClientRect();
    if (rect.width && rect.height) {
      scale = Math.min(
        (window.innerWidth * 0.92) / rect.width,
        (window.innerHeight * 0.92) / rect.height
      );
      applyTransform();
    }
  }

  function close() {
    if (!overlay) return;
    overlay.classList.remove("visible");
    document.body.classList.remove("mermaid-zoom-open");
    dragging = false;

    // Return the live node to exactly where it was.
    if (host && placeholder && placeholder.parentNode) {
      if (savedCss) host.setAttribute("style", savedCss);
      else host.removeAttribute("style");
      placeholder.parentNode.replaceChild(host, placeholder);
    }
    host = null;
    placeholder = null;
  }

  document.addEventListener("click", function (e) {
    if (!e.target || !e.target.closest) return;
    var node = e.target.closest(".mermaid");
    if (!node || overlay && node.closest(".mermaid-zoom-overlay")) return;
    open(node);
  });

  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") close();
  });
})();
