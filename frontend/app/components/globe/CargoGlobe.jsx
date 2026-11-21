"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import dynamic from "next/dynamic";
import { MeshPhongMaterial, Color } from "three";
import { airport, ORIGIN_CODES } from "../../lib/airports";
import "./globe.css";

// react-globe.gl touches `window` at import time → client-only. The wrapper turns the
// `globeRef` prop into a real ref so the dynamic boundary never swallows it.
const Globe = dynamic(
  () =>
    import("react-globe.gl").then((m) => {
      const G = m.default;
      function GlobeWithRef({ globeRef, ...props }) {
        return <G ref={globeRef} {...props} />;
      }
      return GlobeWithRef;
    }),
  { ssr: false, loading: () => null }
);

// Mirrors tokens.css (WebGL can't read CSS variables, so the hex values live here too).
const STATE = {
  booked: { rgb: [31, 157, 85], label: "Booked" }, // --ok
  quoted: { rgb: [10, 132, 255], label: "Quoted" }, // --blue
  active: { rgb: [255, 100, 20], label: "In pricing" }, // --accent
  attention: { rgb: [245, 158, 11], label: "Needs attention" }, // --warn
  closed: { rgb: [100, 116, 139], label: "Closed" }, // slate
};
const STATE_ORDER = ["active", "attention", "quoted", "booked", "closed"];
const ACCENT = "#ff6414";
const INK = "#0b1220";
const ORIGINS = new Set(ORIGIN_CODES);
const INITIAL_POV = { lat: 30, lng: 20, altitude: 2.2 };

const rgba = ([r, g, b], a) => `rgba(${r},${g},${b},${a})`;
const stateOf = (s) => STATE[s] ?? STATE.closed;
const eur = (n) =>
  typeof n === "number"
    ? new Intl.NumberFormat("de-DE", { style: "currency", currency: "EUR", maximumFractionDigits: 0 }).format(n)
    : null;
const esc = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

// Great-circle midpoint (good enough for camera targeting).
function midpoint(a, b) {
  const r = Math.PI / 180;
  const v = (p) => [Math.cos(p.lat * r) * Math.cos(p.lng * r), Math.cos(p.lat * r) * Math.sin(p.lng * r), Math.sin(p.lat * r)];
  const [x1, y1, z1] = v(a);
  const [x2, y2, z2] = v(b);
  const x = x1 + x2, y = y1 + y2, z = z1 + z2;
  const lat = Math.atan2(z, Math.hypot(x, y)) / r;
  const lng = Math.atan2(y, x) / r;
  // angular distance → pull the camera back for long routes
  const dot = Math.min(1, Math.max(-1, x1 * x2 + y1 * y2 + z1 * z2));
  const dist = Math.acos(dot) / r; // degrees
  return { lat, lng, altitude: Math.min(2.6, 1.5 + dist / 120) };
}

function tooltip(d) {
  const st = stateOf(d.state);
  const rows = [
    d.airline && ["Airline", esc(d.airline)],
    eur(d.totalEur) && ["Total", eur(d.totalEur)],
    typeof d.days === "number" && ["Transit", `${d.days} ${d.days === 1 ? "day" : "days"}`],
  ].filter(Boolean);
  return `
  <div style="font-family:var(--sans);color:${INK};background:rgba(255,255,255,0.82);
    -webkit-backdrop-filter:saturate(180%) blur(24px);backdrop-filter:saturate(180%) blur(24px);
    border:1px solid rgba(255,255,255,0.8);border-radius:12px;padding:10px 12px;min-width:168px;
    box-shadow:0 2px 6px rgba(15,23,42,0.08),0 16px 48px rgba(15,23,42,0.12);">
    <div style="font-family:var(--mono);font-size:13px;font-weight:600;letter-spacing:0.02em;">
      ${esc(d.origin)} <span style="color:#5b6475">→</span> ${esc(d.destination)}
    </div>
    ${d.label ? `<div style="font-size:12px;color:#334155;margin-top:2px;">${esc(d.label)}</div>` : ""}
    <div style="display:grid;grid-template-columns:auto 1fr;gap:2px 12px;margin-top:8px;font-size:12px;">
      ${rows
        .map(
          ([k, v]) =>
            `<span style="color:#5b6475">${k}</span><span style="text-align:right;font-variant-numeric:tabular-nums;">${v}</span>`
        )
        .join("")}
    </div>
    <div style="display:flex;align-items:center;gap:6px;margin-top:8px;font-size:11px;font-weight:600;color:${rgba(st.rgb, 1)};">
      <span style="width:7px;height:7px;border-radius:50%;background:${rgba(st.rgb, 1)};"></span>${st.label}
    </div>
  </div>`;
}

const warned = new Set();

// Stable accessors (new function identities would make globe.gl re-digest the layer every render).
// Seamless flow: dash + gap = 0.25, so the pattern repeats exactly 4× along every arc and
// there is never an empty phase or visible restart. Ends fade (see arcColor) so dashes
// ease in at the origin and dissolve at the destination instead of popping.
const DASH_PERIOD = 0.25;
// "hit" arcs are wide invisible tubes that only exist to make hovering/clicking easy. Their dash
// pattern discards every fragment (initial gap > arc length), so they never draw or write depth
// and can't hide other arcs — but the raycaster still hits their geometry.
const dashLength = (a) => (a.kind === "track" ? 1 : a.kind === "hit" ? 0.0001 : 0.07);
const dashGap = (a) => (a.kind === "track" ? 0 : a.kind === "hit" ? 10 : DASH_PERIOD - 0.07);
const dashInitialGap = (a) => (a.kind === "track" ? 0 : a.kind === "hit" ? 2 : ((a.id * 0.37) % 1) * DASH_PERIOD);
const dashAnimate = (a) => (a.kind === "dash" ? 6000 : 0);
const noDashAnimate = () => 0;
const landColor = () => "rgba(71,85,105,0.42)";
const pointRadius = (p) => (p.isOrigin ? 0.42 : 0.26);
const pointTip = (p) =>
  `<div class="cg-pt-tip">${esc(p.code)} · ${esc(p.city)}<br/><span>${esc(p.name)}</span></div>`;

export default function CargoGlobe({ routes = [], selectedId = null, onSelect, height, compact = false, paused = false }) {
  const wrapRef = useRef(null);
  const globeRef = useRef(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [countries, setCountries] = useState([]);
  const [focusCode, setFocusCode] = useState(null);
  const [ready, setReady] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(false);

  // --- size: follow the wrapper
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => {
      const { width, height: h } = e.contentRect;
      if (!width || !h) return; // hidden (view kept alive): keep the last size instead of collapsing to 0
      setSize((s) => (Math.round(width) === s.w && Math.round(h) === s.h ? s : { w: Math.round(width), h: Math.round(h) }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // --- stop the WebGL render loop while the view is hidden (kept mounted for instant switching)
  useEffect(() => {
    const g = globeRef.current;
    if (!g || !ready) return;
    if (paused) g.pauseAnimation?.();
    else g.resumeAnimation?.();
  }, [paused, ready]);

  // --- reduced motion
  useEffect(() => {
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    const on = () => setReducedMotion(mq.matches);
    on();
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);

  // --- land layer (local asset, works offline)
  useEffect(() => {
    let alive = true;
    fetch("/globe/countries.geojson")
      .then((r) => (r.ok ? r.json() : null))
      .then((geo) => alive && geo && setCountries(geo.features))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  const globeMaterial = useMemo(
    () =>
      new MeshPhongMaterial({
        color: new Color("#f3f7fc"),
        emissive: new Color("#dfe9f6"),
        emissiveIntensity: 0.35,
        shininess: 6,
        transparent: true,
        opacity: 0.94,
      }),
    []
  );

  // --- data
  // The dashboard polls every 2 s and hands us brand-new route objects each time. globe.gl joins
  // data by object identity, so new objects would rebuild every arc and restart its dash animation
  // (the visible "loop reset"). Reuse the previous objects for routes whose content is unchanged.
  const [hoverId, setHoverId] = useState(null);
  const cache = useRef(new Map());
  const signature = routes
    .map((r) => [r.id, r.origin, r.destination, r.state, r.airline, r.totalEur, r.days, r.label].join("|"))
    .join(";");
  const arcEntries = useMemo(() => {
    const out = [];
    const nextCache = new Map();
    for (const r of routes) {
      const o = airport(r.origin);
      const d = airport(r.destination);
      if (!o || !d) {
        const bad = !o ? r.origin : r.destination;
        if (!warned.has(bad)) {
          warned.add(bad);
          console.warn(`[CargoGlobe] unknown airport code "${bad}" — route ${r.id} skipped`);
        }
        continue;
      }
      const sig = [r.origin, r.destination, r.state, r.airline, r.totalEur, r.days, r.label].join("|");
      const prev = cache.current.get(r.id);
      const entry =
        prev && prev.sig === sig
          ? prev
          : (() => {
              const arc = { ...r, origin: o.code, destination: d.code, startLat: o.lat, startLng: o.lng, endLat: d.lat, endLng: d.lng };
              // Each route renders twice: a faint solid "track" + animated dashes travelling origin → destination.
              return { sig, arc, track: { ...arc, kind: "track" }, dash: { ...arc, kind: "dash" }, hit: { ...arc, kind: "hit" } };
            })();
      nextCache.set(r.id, entry);
      out.push(entry);
    }
    cache.current = nextCache;
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);

  const arcs = useMemo(() => arcEntries.map((e) => e.arc), [arcEntries]);
  const arcLayers = useMemo(() => arcEntries.flatMap((e) => [e.track, e.dash, e.hit]), [arcEntries]);

  const airportsInUse = useMemo(() => {
    const m = new Map();
    for (const a of arcs) {
      for (const code of [a.origin, a.destination]) {
        if (!m.has(code)) {
          const ap = airport(code);
          m.set(code, { ...ap, isOrigin: ORIGINS.has(code) });
        }
      }
    }
    return [...m.values()];
  }, [arcs]);

  // Drop focus if the focused airport vanished from the data.
  useEffect(() => {
    if (focusCode && !airportsInUse.some((a) => a.code === focusCode)) setFocusCode(null);
  }, [airportsInUse, focusCode]);

  const hasSel = selectedId != null && arcs.some((a) => a.id === selectedId);
  const isLit = useCallback(
    (a) => {
      if (hasSel) return a.id === selectedId;
      if (focusCode) return a.origin === focusCode || a.destination === focusCode;
      return true;
    },
    [hasSel, selectedId, focusCode]
  );
  const dimming = hasSel || !!focusCode;

  const arcColor = useCallback(
    (a) => {
      const c = stateOf(a.state).rgb;
      const lit = isLit(a);
      const hi = lit ? 1 : 0.35;
      if (a.kind === "hit") return "rgba(0,0,0,0)";
      if (a.kind === "track") return rgba(c, hi * (lit && dimming ? 0.55 : 0.3));
      // transparent at both ends → dashes fade in at the origin and out at the destination
      return [rgba(c, 0), rgba(c, hi * 0.9), rgba(c, hi), rgba(c, hi), rgba(c, 0)];
    },
    [isLit, dimming]
  );
  const arcStroke = useCallback(
    (a) => {
      const sel = hasSel && a.id === selectedId;
      const hov = a.id === hoverId;
      if (a.kind === "hit") return compact ? 2.2 : 2.8;
      if (a.kind === "track") return sel ? 0.5 : hov ? 0.42 : compact ? 0.22 : 0.28;
      return sel ? 1.1 : hov ? 0.95 : compact ? 0.5 : 0.65;
    },
    [hasSel, selectedId, compact, hoverId]
  );

  const pointColor = useCallback(
    (p) => {
      const lit = !focusCode || p.code === focusCode || arcs.some((a) => isLit(a) && (a.origin === p.code || a.destination === p.code));
      const base = p.isOrigin ? ACCENT : INK;
      if (lit) return base;
      return p.isOrigin ? "rgba(255,100,20,0.4)" : "rgba(11,18,32,0.3)";
    },
    [focusCode, arcs, isLit]
  );

  const labelEl = useCallback(
    (p) => {
      // globe.gl owns the outer element's transform (CSS2D positioning) → offset the inner one.
      const pin = document.createElement("div");
      pin.className = "cg-pin";
      const el = document.createElement("span");
      el.className = `cg-code${p.isOrigin ? ` cg-code--origin cg-code--${p.code.toLowerCase()}` : ""}`;
      el.textContent = p.code;
      pin.appendChild(el);
      return pin;
    },
    []
  );
  const labelVisibility = useCallback((el, visible) => {
    el.style.opacity = visible ? "" : "0";
  }, []);
  // dim labels not involved in the current focus
  useEffect(() => {
    const root = wrapRef.current;
    if (!root) return;
    const lit = new Set();
    for (const a of arcs) if (isLit(a)) lit.add(a.origin).add(a.destination);
    root.querySelectorAll(".cg-code").forEach((el) => {
      el.classList.toggle("cg-code--dim", dimming && !lit.has(el.textContent));
    });
  }, [arcs, isLit, dimming, ready, airportsInUse]);

  // --- controls: auto-rotate, pause on interaction
  const idleTimer = useRef(null);
  const autoRotate = !reducedMotion;
  useEffect(() => {
    const g = globeRef.current;
    if (!ready || !g) return;
    const c = g.controls();
    c.autoRotate = autoRotate;
    c.autoRotateSpeed = compact ? 0.25 : 0.35;
    c.enableDamping = true;
    c.dampingFactor = 0.08;
    c.minDistance = 140;
    c.maxDistance = 520;
    const start = () => {
      clearTimeout(idleTimer.current);
      c.autoRotate = false;
    };
    const end = () => {
      clearTimeout(idleTimer.current);
      if (autoRotate) idleTimer.current = setTimeout(() => (c.autoRotate = true), 4000);
    };
    c.addEventListener("start", start);
    c.addEventListener("end", end);
    return () => {
      c.removeEventListener("start", start);
      c.removeEventListener("end", end);
      clearTimeout(idleTimer.current);
    };
  }, [ready, autoRotate, compact]);

  // globe.gl may fire onGlobeReady more than once (e.g. after layer re-digests) → only set the initial view once.
  const didInit = useRef(false);
  const onGlobeReady = useCallback(() => {
    if (didInit.current) return;
    didInit.current = true;
    globeRef.current?.pointOfView(INITIAL_POV, 0);
    setReady(true);
  }, []);

  // --- fly to selection
  useEffect(() => {
    const g = globeRef.current;
    if (!ready || !g || !hasSel) return;
    const a = arcs.find((x) => x.id === selectedId);
    if (!a) return;
    const c = g.controls();
    c.autoRotate = false;
    clearTimeout(idleTimer.current);
    g.pointOfView(midpoint({ lat: a.startLat, lng: a.startLng }, { lat: a.endLat, lng: a.endLng }), reducedMotion ? 0 : 900);
    if (autoRotate) idleTimer.current = setTimeout(() => (c.autoRotate = true), 8000);
  }, [selectedId, hasSel, ready, arcs, reducedMotion, autoRotate]);

  const handleArcClick = useCallback((a) => onSelect?.(a.id), [onSelect]);
  const handleArcHover = useCallback((a) => setHoverId(a ? a.id : null), []);
  const handlePointClick = useCallback((p) => setFocusCode((cur) => (cur === p.code ? null : p.code)), []);
  const handleGlobeClick = useCallback(() => setFocusCode(null), []);

  const counts = useMemo(() => {
    const c = {};
    for (const a of arcs) c[a.state] = (c[a.state] ?? 0) + 1;
    return c;
  }, [arcs]);

  const w = size.w;
  const h = height ?? size.h;

  return (
    <div
      ref={wrapRef}
      className={`cg-root${compact ? " cg-root--compact" : ""}${ready ? " cg-root--ready" : ""}`}
      style={height ? { height } : undefined}
    >
      {w > 0 && h > 0 && (
        <Globe
          globeRef={globeRef}
          width={w}
          height={h}
          backgroundColor="rgba(0,0,0,0)"
          rendererConfig={{ antialias: true, alpha: true }}
          globeMaterial={globeMaterial}
          showAtmosphere
          atmosphereColor="#b9d8ff"
          atmosphereAltitude={0.16}
          onGlobeReady={onGlobeReady}
          onGlobeClick={handleGlobeClick}
          // land
          hexPolygonsData={countries}
          hexPolygonResolution={3}
          hexPolygonMargin={0.42}
          hexPolygonUseDots
          hexPolygonAltitude={0.004}
          hexPolygonColor={landColor}
          hexPolygonsTransitionDuration={0}
          // routes
          arcsData={arcLayers}
          arcColor={arcColor}
          arcStroke={arcStroke}
          arcAltitudeAutoScale={0.42}
          arcDashLength={dashLength}
          arcDashGap={dashGap}
          arcDashInitialGap={dashInitialGap}
          arcDashAnimateTime={reducedMotion ? noDashAnimate : dashAnimate}
          arcCurveResolution={48}
          arcsTransitionDuration={0}
          arcLabel={tooltip}
          onArcClick={handleArcClick}
          onArcHover={handleArcHover}
          // airports
          pointsData={airportsInUse}
          pointLat="lat"
          pointLng="lng"
          pointColor={pointColor}
          pointAltitude={0.006}
          pointRadius={pointRadius}
          pointResolution={16}
          pointsTransitionDuration={0}
          pointLabel={pointTip}
          onPointClick={handlePointClick}
          htmlElementsData={airportsInUse}
          htmlLat="lat"
          htmlLng="lng"
          htmlAltitude={0.01}
          htmlElement={labelEl}
          htmlElementVisibilityModifier={labelVisibility}
          htmlTransitionDuration={0}
        />
      )}

      {arcs.length === 0 && (
        <div className="cg-empty glass">No routes yet — quoted shipments will appear here.</div>
      )}

      {!compact && arcs.length > 0 && (
        <div className="cg-legend glass" aria-label="Route legend">
          <div className="cg-legend__head">
            <span className="cg-legend__count">{arcs.length}</span> {arcs.length === 1 ? "route" : "routes"}
            {focusCode && (
              <button type="button" className="cg-legend__chip" onClick={() => setFocusCode(null)}>
                {focusCode} ✕
              </button>
            )}
          </div>
          <ul>
            {STATE_ORDER.filter((s) => counts[s]).map((s) => (
              <li key={s}>
                <span className="cg-legend__swatch" style={{ background: rgba(STATE[s].rgb, 1) }} />
                <span>{STATE[s].label}</span>
                <span className="cg-legend__n">{counts[s]}</span>
              </li>
            ))}
            <li className="cg-legend__sep">
              <span className="cg-legend__dot cg-legend__dot--origin" />
              <span>German hub</span>
            </li>
          </ul>
        </div>
      )}
    </div>
  );
}
