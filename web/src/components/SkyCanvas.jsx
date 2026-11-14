import { useEffect, useRef } from "react";

export function SkyCanvas({ rgb = [229, 44, 41], warmRgb = [255, 118, 92] }) {
  const hostRef = useRef(null);
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const host = hostRef.current;
    if (!canvas || !host) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const [r, g, b] = rgb;
    const [wr, wg, wb] = warmRgb;

    const renderScene = () => {
      const rect = host.getBoundingClientRect();
      const width = Math.max(320, Math.floor(rect.width));
      const height = Math.max(240, Math.floor(rect.height));
      const px = 6;
      canvas.width = width;
      canvas.height = height;

      const root = document.documentElement;
      const isDark =
        root.classList.contains("dark") || root.getAttribute("data-theme") === "dark";
      const styles = getComputedStyle(host);
      const canvasBg =
        styles.getPropertyValue("--color-canvas-default").trim() ||
        (isDark ? "rgb(0, 0, 0)" : "rgb(253, 253, 253)");

      ctx.clearRect(0, 0, width, height);

      ctx.fillStyle = isDark
        ? "rgba(255, 255, 255, 0.24)"
        : `rgba(${r}, ${g}, ${b}, 0.16)`;
      for (let i = 0; i < 60; i++) {
        const sx = Math.floor(((i * 211 + 37) % width) / px) * px;
        const sy = Math.floor(((i * 89 + 17) % Math.floor(height * 0.54)) / px) * px;
        const size = i % 5 === 0 ? 3 : 2;
        ctx.fillRect(sx, sy, size, size);
      }

      const drawCloud = (cx, cy, span) => {
        ctx.fillStyle = isDark
          ? `rgba(${wr}, ${wg}, ${wb}, 0.13)`
          : `rgba(${r}, ${g}, ${b}, 0.11)`;
        ctx.fillRect(cx - span * 0.5, cy, span, px);
        ctx.fillRect(cx - span * 0.34, cy - px, span * 0.68, px);
        ctx.fillStyle = isDark
          ? `rgba(${r}, ${g}, ${b}, 0.08)`
          : `rgba(${r}, ${g}, ${b}, 0.06)`;
        ctx.fillRect(cx - span * 0.42, cy + px, span * 0.84, px);
      };

      drawCloud(
        Math.floor((width * 0.16) / px) * px,
        Math.floor((height * 0.65) / px) * px,
        96
      );
      drawCloud(
        Math.floor((width * 0.48) / px) * px,
        Math.floor((height * 0.72) / px) * px,
        84
      );

      const discX = width * 0.64;
      const discY = height * 0.83;
      const discR = Math.min(98, width * 0.11);
      const glow = ctx.createRadialGradient(
        discX,
        discY - 12,
        discR * 0.2,
        discX,
        discY - 12,
        discR * 2.5
      );
      glow.addColorStop(
        0,
        isDark ? `rgba(${wr}, ${wg}, ${wb}, 0.28)` : `rgba(${r}, ${g}, ${b}, 0.18)`
      );
      glow.addColorStop(
        0.55,
        isDark ? `rgba(${r}, ${g}, ${b}, 0.14)` : `rgba(${r}, ${g}, ${b}, 0.09)`
      );
      glow.addColorStop(1, "rgba(0, 0, 0, 0)");
      ctx.fillStyle = glow;
      ctx.fillRect(0, discY - discR * 2.5, width, discR * 3.5);

      for (let y = -discR; y <= 0; y += px) {
        const span = Math.sqrt(Math.max(0, discR * discR - y * y));
        const snappedW = Math.floor((span * 2) / px) * px;
        const ratio = 1 + y / discR;
        ctx.fillStyle = isDark
          ? `rgba(${wr}, ${Math.round(wg - ratio * 24)}, ${Math.round(wb - ratio * 22)}, ${0.62 - ratio * 0.22})`
          : `rgba(${r}, ${g}, ${b}, ${0.24 - ratio * 0.08})`;
        ctx.fillRect(
          Math.floor((discX - snappedW / 2) / px) * px,
          discY + y,
          snappedW,
          px - 1
        );
      }

      ctx.fillStyle = canvasBg;
      const cols = Math.ceil(width / px);
      for (let c = 0; c < cols; c++) {
        const wave =
          Math.sin(c * 0.14) * 14 + Math.cos(c * 0.37) * 9 + ((c * 17) % 16);
        const ridge = Math.max(12, Math.floor((height * 0.14 + wave) / px) * px);
        ctx.fillRect(c * px, height - ridge, px + 1, ridge);
      }
    };

    renderScene();
    const ro = new ResizeObserver(renderScene);
    ro.observe(host);
    const mo = new MutationObserver(renderScene);
    mo.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["class", "data-theme"],
    });

    return () => {
      ro.disconnect();
      mo.disconnect();
    };
  }, [rgb, warmRgb]);

  return (
    <div
      ref={hostRef}
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 overflow-hidden"
    >
      <canvas ref={canvasRef} className="block h-full w-full" />
    </div>
  );
}
