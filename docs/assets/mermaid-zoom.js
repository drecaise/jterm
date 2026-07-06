/*
 * Click-to-zoom for Mermaid diagrams.
 *
 * Material renders each ```mermaid fence as <pre class="mermaid">, then mermaid.js
 * swaps in an inline <svg> at runtime. Clicking a diagram opens a full-screen overlay
 * holding a clone of that SVG, with wheel-zoom and drag-pan. Because navigation.instant
 * swaps page bodies without a full reload, everything hangs off a single delegated
 * listener on `document` (which persists), attached exactly once.
 */
(function () {
  if (window.__mermaidZoomInstalled) return;
  window.__mermaidZoomInstalled = true;

  var overlay, stage, svgHolder, closeBtn;
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

    // Backdrop (but not the SVG) closes on click.
    overlay.addEventListener("click", function (e) {
      if (e.target === overlay || e.target === stage) close();
    });
    closeBtn.addEventListener("click", close);

    stage.addEventListener("wheel", function (e) {
      e.preventDefault();
      var factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
      var next = Math.min(12, Math.max(0.3, scale * factor));
      scale = next;
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

  function open(sourceSvg) {
    if (!overlay || !document.body.contains(overlay)) buildOverlay();

    scale = 1; tx = 0; ty = 0;
    var clone = sourceSvg.cloneNode(true);
    clone.removeAttribute("id");
    // Let the SVG fill the stage instead of its baked content-width size.
    clone.style.maxWidth = "92vw";
    clone.style.maxHeight = "92vh";
    clone.style.width = "auto";
    clone.style.height = "auto";
    svgHolder.innerHTML = "";
    svgHolder.appendChild(clone);
    applyTransform();

    document.body.appendChild(overlay);
    document.body.classList.add("mermaid-zoom-open");
    // Force reflow so the fade-in transition runs.
    void overlay.offsetWidth;
    overlay.classList.add("visible");
  }

  function close() {
    if (!overlay) return;
    overlay.classList.remove("visible");
    document.body.classList.remove("mermaid-zoom-open");
    dragging = false;
  }

  document.addEventListener("click", function (e) {
    var pre = e.target.closest && e.target.closest("pre.mermaid");
    if (!pre) return;
    var svg = pre.querySelector("svg");
    if (!svg) return; // not rendered yet
    open(svg);
  });

  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") close();
  });
})();
