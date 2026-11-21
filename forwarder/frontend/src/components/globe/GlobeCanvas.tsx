"use client";

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Globe, { type GlobeMethods } from "react-globe.gl";
import * as THREE from "three";
import { GERMAN_HUBS } from "./demoShipments";
import { arcAltitude, flightState } from "./flight";
import { PHASE_COLOR, STATUS_COLOR, rgba, tooltipHtml } from "./format";
import {
  buildArcCurve,
  createGlowTexture,
  createPlaneGeometry,
  createStarfield,
  disposeObject,
  toLatLng,
  toVec,
} from "./three-helpers";
import type { ActiveShipment, ShipmentPoint, TimeMode } from "./types";

export interface GlobeCanvasProps {
  shipments: ActiveShipment[];
  timeMode: TimeMode;
  selectedId: number | null;
  /** Row hovered in the side panel (highlights the arc without flying). */
  focusId: number | null;
  onSelect: (id: number | null) => void;
  /** Flat [lat, lng, ...] of OpenSky aircraft, or null when the layer is off. */
  traffic: Float32Array | null;
}

const HOME_POV = { lat: 30, lng: 28, altitude: 2.35 };
const RESUME_ROTATE_MS = 7_000;
const LANDED_COLOR = "#4ade80";

interface ArcDatum {
  kind: "track" | "flow";
  s: ActiveShipment;
  alt: number;
}

interface AirportDatum extends ShipmentPoint {
  hub: boolean;
}

interface Rig {
  root: THREE.Group;
  plane: THREE.Mesh;
  glow: THREE.Sprite;
  ring: THREE.Mesh;
  curve: THREE.CubicBezierCurve3;
  color: THREE.Color;
}

interface SharedRes {
  planeGeo: THREE.BufferGeometry;
  planeMat: THREE.MeshBasicMaterial;
  ringGeo: THREE.RingGeometry;
  glowTex: THREE.Texture;
}

function createShared(): SharedRes {
  return {
    planeGeo: createPlaneGeometry(),
    planeMat: new THREE.MeshBasicMaterial({ color: "#f5f8ff" }),
    ringGeo: new THREE.RingGeometry(0.75, 1, 48),
    glowTex: createGlowTexture(),
  };
}

function GlobeCanvasImpl({ shipments, timeMode, selectedId, focusId, onSelect, traffic }: GlobeCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const globeRef = useRef<GlobeMethods | undefined>(undefined);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const [ready, setReady] = useState(false);
  const [countries, setCountries] = useState<object[]>([]);
  const [hoverId, setHoverId] = useState<number | null>(null);

  // Mutable state read by the rAF loop (never triggers React renders).
  const sharedRef = useRef<SharedRes | null>(null);
  const rigsRef = useRef(new Map<ActiveShipment, Rig>());
  const shipmentsRef = useRef(shipments);
  const modeRef = useRef(timeMode);
  const selectedRef = useRef(selectedId);
  const onSelectRef = useRef(onSelect);
  const htmlCache = useRef(new Map<string, HTMLElement>());
  const trafficObjRef = useRef<THREE.Points | null>(null);

  useEffect(() => {
    shipmentsRef.current = shipments;
    modeRef.current = timeMode;
    selectedRef.current = selectedId;
    onSelectRef.current = onSelect;
  });

  // ---- Size: follow the container ---------------------------------------------------------
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setSize({ w: Math.max(1, Math.round(width)), h: Math.max(1, Math.round(height)) });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // ---- Land layer: locally bundled Natural Earth 110m outlines -----------------------------
  useEffect(() => {
    const ctrl = new AbortController();
    fetch("/globe/countries.geojson", { signal: ctrl.signal })
      .then((r) => r.json())
      .then((g: { features: object[] }) => setCountries(g.features))
      .catch(() => {});
    return () => ctrl.abort();
  }, []);

  const globeMaterial = useMemo(
    () =>
      new THREE.MeshPhongMaterial({
        color: new THREE.Color("#071029"),
        emissive: new THREE.Color("#030815"),
        emissiveIntensity: 1,
        specular: new THREE.Color("#1b2c5e"),
        shininess: 9,
      }),
    [],
  );
  useEffect(() => () => globeMaterial.dispose(), [globeMaterial]);

  // ---- Derived layer data -----------------------------------------------------------------
  const arcs = useMemo<ArcDatum[]>(
    () =>
      shipments.flatMap((s) => {
        const alt = arcAltitude(s);
        return [
          { kind: "track", s, alt },
          { kind: "flow", s, alt },
        ];
      }),
    [shipments],
  );

  const airports = useMemo<AirportDatum[]>(() => {
    const m = new Map<string, AirportDatum>();
    for (const s of shipments) {
      for (const p of [s.origin, s.destination]) {
        if (!m.has(p.iata)) m.set(p.iata, { ...p, hub: GERMAN_HUBS.has(p.iata) });
      }
    }
    return [...m.values()];
  }, [shipments]);

  const hubs = useMemo(() => airports.filter((a) => a.hub), [airports]);

  // Emphasis: selection wins over hover; panel hover acts like hover.
  const emphasisId = selectedId ?? focusId ?? hoverId;

  const arcColor = useCallback(
    (o: object) => {
      const d = o as ArcDatum;
      const c = STATUS_COLOR[d.s.status];
      const dim = emphasisId != null && d.s.bookingId !== emphasisId;
      const on = emphasisId != null && d.s.bookingId === emphasisId;
      if (d.kind === "track") {
        const a = dim ? 0.06 : on ? 0.45 : 0.2;
        return [rgba(c, a), rgba(c, a)];
      }
      if (dim) return [rgba(c, 0.02), rgba(c, 0.12)];
      return [rgba(c, on ? 0.6 : 0.15), rgba(c, 1)];
    },
    [emphasisId],
  );

  const arcStroke = useCallback(
    (o: object) => {
      const d = o as ArcDatum;
      const on = emphasisId != null && d.s.bookingId === emphasisId;
      if (d.kind === "track") return on ? 0.35 : 0.18;
      return on ? 0.95 : 0.5;
    },
    [emphasisId],
  );

  const htmlElement = useCallback((o: object) => {
    const a = o as AirportDatum;
    const cached = htmlCache.current.get(a.iata);
    if (cached) return cached;
    const el = document.createElement("div");
    const place = HUB_LABEL_PLACEMENT[a.iata];
    el.className = `fwd-ap${a.hub ? " fwd-ap-hub" : ""}${place?.side === "l" ? " fwd-ap-l" : ""}`;
    const city = a.city ? `<i>${a.city.replace(/[<>&]/g, "")}</i>` : "";
    const top = place ? ` style="top:${place.top}px"` : "";
    el.innerHTML = `<span class="fwd-ap-dot"></span><span class="fwd-ap-label"${top}><b>${a.iata}</b>${city}</span>`;
    htmlCache.current.set(a.iata, el);
    return el;
  }, []);

  // ---- Planes (custom layer): objects are created by globe.gl, animated by our rAF loop -----
  const customThreeObject = useCallback((o: object) => {
    const s = o as ActiveShipment;
    const res = (sharedRef.current ??= createShared());
    const color = new THREE.Color(STATUS_COLOR[s.status]);
    const root = new THREE.Group();
    const plane = new THREE.Mesh(res.planeGeo, res.planeMat);
    const glow = new THREE.Sprite(
      new THREE.SpriteMaterial({
        map: res.glowTex,
        color,
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
      }),
    );
    const ring = new THREE.Mesh(
      res.ringGeo,
      new THREE.MeshBasicMaterial({
        color,
        transparent: true,
        side: THREE.DoubleSide,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
      }),
    );
    root.add(glow, ring, plane);
    const rig: Rig = { root, plane, glow, ring, curve: buildArcCurve(s), color };
    rigsRef.current.set(s, rig);
    return root;
  }, []);
  const customThreeObjectUpdate = useCallback(() => {
    /* positions are driven by the animation loop below */
  }, []);

  // Drop rigs for shipments that left the dataset.
  useEffect(() => {
    const live = new Set(shipments);
    for (const [s, rig] of rigsRef.current) {
      if (!live.has(s)) {
        (rig.glow.material as THREE.Material).dispose();
        (rig.ring.material as THREE.Material).dispose();
        rigsRef.current.delete(s);
      }
    }
  }, [shipments]);

  // ---- Animation loop ---------------------------------------------------------------------
  useEffect(() => {
    if (!ready) return;
    const globe = globeRef.current;
    if (!globe) return;
    const camera = globe.camera();
    const p = new THREE.Vector3();
    const n = new THREE.Vector3();
    const tan = new THREE.Vector3();
    const look = new THREE.Vector3();
    const landedColor = new THREE.Color(LANDED_COLOR);
    const awaitColor = new THREE.Color(PHASE_COLOR.awaiting);
    let raf = 0;


    const frame = () => {
      raf = requestAnimationFrame(frame);
      const now = Date.now();
      const mode = modeRef.current;
      const sel = selectedRef.current;
      const camDist = camera.position.length();
      const k = THREE.MathUtils.clamp(camDist / 340, 0.32, 1.25);
      const list = shipmentsRef.current;

      for (let i = 0; i < list.length; i++) {
        const s = list[i];
        const rig = rigsRef.current.get(s);
        if (!rig) continue;
        const st = flightState(s, mode, now, i);
        const isSel = sel === s.bookingId;
        const dimmed = sel != null && !isSel;
        const boost = isSel ? 1.55 : 1;
        const glowMat = rig.glow.material as THREE.SpriteMaterial;
        const ringMat = rig.ring.material as THREE.MeshBasicMaterial;

        if (st.phase === "air") {
          rig.curve.getPoint(st.t, p);
          n.copy(p).normalize();
          rig.curve.getTangent(Math.min(0.999, Math.max(0.001, st.t)), tan);
          p.addScaledVector(n, 0.5 * k);
          rig.plane.position.copy(p);
          rig.plane.up.copy(n);
          rig.plane.lookAt(look.copy(p).add(tan));
          rig.plane.scale.setScalar(1.7 * k * boost);
          rig.plane.visible = true;
          rig.glow.position.copy(p);
          rig.glow.scale.setScalar(7 * k * boost);
          glowMat.color.copy(rig.color);
          glowMat.opacity = dimmed ? 0.25 : 0.95;
          rig.ring.visible = false;
        } else {
          const atEnd = st.phase === "landed";
          rig.curve.getPoint(atEnd ? 1 : 0, p);
          n.copy(p).normalize();
          rig.curve.getTangent(atEnd ? 0.98 : 0.02, tan);
          tan.addScaledVector(n, -tan.dot(n)).normalize();
          // Parked on the apron, just above the surface.
          p.addScaledVector(n, 0.25 * k);
          rig.plane.position.copy(p);
          rig.plane.up.copy(n);
          rig.plane.lookAt(look.copy(p).add(tan));
          rig.plane.scale.setScalar((atEnd ? 0.8 : 1) * k * boost);
          rig.plane.visible = true;

          const pulse = 0.5 + 0.5 * Math.sin(now / 260 + i);
          rig.glow.position.copy(p);
          rig.ring.position.copy(n).multiplyScalar(100.35);
          rig.ring.lookAt(look.copy(n).multiplyScalar(200));
          if (atEnd) {
            glowMat.color.copy(landedColor);
            glowMat.opacity = dimmed ? 0.2 : 0.6;
            rig.glow.scale.setScalar(5 * k * boost);
            ringMat.color.copy(landedColor);
            ringMat.opacity = dimmed ? 0.2 : 0.85;
            rig.ring.scale.setScalar(2.1 * k * boost);
          } else {
            // Awaiting departure: pulse at the origin.
            const ph = (now % 1600) / 1600;
            glowMat.color.copy(awaitColor);
            glowMat.opacity = (dimmed ? 0.25 : 0.55) + 0.35 * pulse;
            rig.glow.scale.setScalar((5 + 2.5 * pulse) * k * boost);
            ringMat.color.copy(awaitColor);
            ringMat.opacity = (dimmed ? 0.3 : 1) * (1 - ph);
            rig.ring.scale.setScalar((1.2 + 4.2 * ph) * k * boost);
          }
          rig.ring.visible = true;
        }
      }
    };
    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, [ready]);

  // ---- Scene extras, controls, intro flight -----------------------------------------------
  useEffect(() => {
    if (!ready) return;
    const globe = globeRef.current;
    if (!globe) return;
    const scene = globe.scene();
    const stars = createStarfield();
    scene.add(stars);

    const controls = globe.controls();
    controls.autoRotate = true;
    controls.autoRotateSpeed = 0.35;
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.minDistance = 130;
    controls.maxDistance = 700;

    let resume: ReturnType<typeof setTimeout> | undefined;
    const onStart = () => {
      controls.autoRotate = false;
      if (resume) clearTimeout(resume);
    };
    const onEnd = () => {
      if (resume) clearTimeout(resume);
      resume = setTimeout(() => {
        if (selectedRef.current == null) controls.autoRotate = true;
      }, RESUME_ROTATE_MS);
    };
    controls.addEventListener("start", onStart);
    controls.addEventListener("end", onEnd);

    globe.pointOfView({ ...HOME_POV, altitude: 3.6 }, 0);
    globe.pointOfView(HOME_POV, 2600);

    return () => {
      if (resume) clearTimeout(resume);
      controls.removeEventListener("start", onStart);
      controls.removeEventListener("end", onEnd);
      scene.remove(stars);
      disposeObject(stars);
    };
  }, [ready]);

  // ---- Camera flight to the selected plane -------------------------------------------------
  const hadSelection = useRef(false);
  useEffect(() => {
    if (!ready) return;
    const globe = globeRef.current;
    if (!globe) return;
    const controls = globe.controls();
    if (selectedId == null) {
      if (hadSelection.current) {
        globe.pointOfView({ ...globe.pointOfView(), altitude: HOME_POV.altitude }, 1400);
        controls.autoRotate = true;
      }
      hadSelection.current = false;
      return;
    }
    hadSelection.current = true;
    controls.autoRotate = false;
    const entry = [...rigsRef.current.entries()].find(([s]) => s.bookingId === selectedId);
    let target: { lat: number; lng: number };
    if (entry) {
      target = toLatLng(entry[1].plane.position);
    } else {
      const s = shipmentsRef.current.find((x) => x.bookingId === selectedId);
      if (!s) return;
      target = { lat: s.origin.lat, lng: s.origin.lon };
    }
    globe.pointOfView({ ...target, altitude: 1.45 }, 1600);
  }, [selectedId, ready]);

  // ---- OpenSky ambience layer --------------------------------------------------------------
  useEffect(() => {
    if (!ready) return;
    const globe = globeRef.current;
    if (!globe) return;
    const scene = globe.scene();
    if (!traffic || traffic.length === 0) {
      if (trafficObjRef.current) {
        scene.remove(trafficObjRef.current);
        disposeObject(trafficObjRef.current);
        trafficObjRef.current = null;
      }
      return;
    }
    const count = traffic.length / 2;
    const pos = new Float32Array(count * 3);
    const v = new THREE.Vector3();
    for (let i = 0; i < count; i++) {
      toVec(traffic[i * 2], traffic[i * 2 + 1], 0.012, v);
      pos[i * 3] = v.x;
      pos[i * 3 + 1] = v.y;
      pos[i * 3 + 2] = v.z;
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
    let pts = trafficObjRef.current;
    if (pts) {
      pts.geometry.dispose();
      pts.geometry = geo;
    } else {
      pts = new THREE.Points(
        geo,
        new THREE.PointsMaterial({
          color: "#9fb4d9",
          size: 1.4,
          sizeAttenuation: false,
          transparent: true,
          opacity: 0.32,
          depthWrite: false,
        }),
      );
      pts.name = "opensky-traffic";
      scene.add(pts);
      trafficObjRef.current = pts;
    }
  }, [traffic, ready]);

  // ---- Free GPU resources on unmount ------------------------------------------------------
  // (dispose() only releases GPU buffers; three re-uploads lazily if an object renders again,
  // which keeps React Strict Mode's simulated unmount/remount safe.)
  useEffect(() => {
    const rigs = rigsRef.current;
    return () => {
      for (const rig of rigs.values()) {
        (rig.glow.material as THREE.Material).dispose();
        (rig.ring.material as THREE.Material).dispose();
      }
      const res = sharedRef.current;
      if (res) {
        res.planeGeo.dispose();
        res.planeMat.dispose();
        res.ringGeo.dispose();
        res.glowTex.dispose();
      }
      if (trafficObjRef.current) disposeObject(trafficObjRef.current);
    };
  }, []);

  const handleArcClick = useCallback((o: object) => {
    onSelectRef.current((o as ArcDatum).s.bookingId);
  }, []);
  const handleArcHover = useCallback((o: object | null) => {
    setHoverId(o ? (o as ArcDatum).s.bookingId : null);
  }, []);
  const handlePlaneClick = useCallback((o: object) => {
    onSelectRef.current((o as ActiveShipment).bookingId);
  }, []);
  const handlePlaneHover = useCallback((o: object | null) => {
    setHoverId(o ? (o as ActiveShipment).bookingId : null);
  }, []);
  const handleGlobeClick = useCallback(() => onSelectRef.current(null), []);

  return (
    <div ref={containerRef} className="fwd-globe absolute inset-0">
      <style>{GLOBE_CSS}</style>
      {size && (
        <Globe
          ref={globeRef}
          width={size.w}
          height={size.h}
          backgroundColor="rgba(0,0,0,0)"
          rendererConfig={{ antialias: true, alpha: true, powerPreference: "high-performance" }}
          globeMaterial={globeMaterial}
          showAtmosphere
          atmosphereColor="#4d7cff"
          atmosphereAltitude={0.2}
          onGlobeReady={() => setReady(true)}
          onGlobeClick={handleGlobeClick}
          // Dot-matrix land
          hexPolygonsData={countries}
          hexPolygonResolution={3}
          hexPolygonMargin={0.52}
          hexPolygonUseDots
          hexPolygonDotResolution={6}
          hexPolygonAltitude={0.004}
          hexPolygonsTransitionDuration={0}
          hexPolygonColor={hexColor}
          // Routes
          arcsData={arcs}
          arcStartLat={arcStartLat}
          arcStartLng={arcStartLng}
          arcEndLat={arcEndLat}
          arcEndLng={arcEndLng}
          arcAltitude="alt"
          arcColor={arcColor}
          arcStroke={arcStroke}
          arcCurveResolution={96}
          arcCircularResolution={6}
          arcDashLength={arcDashLength}
          arcDashGap={arcDashGap}
          arcDashInitialGap={arcDashInitialGap}
          arcDashAnimateTime={arcDashAnimateTime}
          arcsTransitionDuration={0}
          arcLabel={arcLabel}
          onArcClick={handleArcClick}
          onArcHover={handleArcHover}
          // Planes
          customLayerData={shipments}
          customThreeObject={customThreeObject}
          customThreeObjectUpdate={customThreeObjectUpdate}
          customLayerLabel={planeLabel}
          onCustomLayerClick={handlePlaneClick}
          onCustomLayerHover={handlePlaneHover}
          // German hubs pulse
          ringsData={hubs}
          ringLat="lat"
          ringLng="lon"
          ringColor={ringColor}
          ringMaxRadius={3.2}
          ringPropagationSpeed={1.6}
          ringRepeatPeriod={1500}
          ringAltitude={0.003}
          // Airport labels
          htmlElementsData={airports}
          htmlLat="lat"
          htmlLng="lon"
          htmlAltitude={0.006}
          htmlElement={htmlElement}
          htmlTransitionDuration={0}
        />
      )}
    </div>
  );
}

/** Hand-placed label offsets so the six tightly clustered German hubs stay legible. */
const HUB_LABEL_PLACEMENT: Record<string, { side: "l" | "r"; top: number }> = {
  HAM: { side: "r", top: -14 },
  DUS: { side: "l", top: -13 },
  CGN: { side: "l", top: 1 },
  FRA: { side: "r", top: -6 },
  STR: { side: "l", top: 3 },
  MUC: { side: "r", top: 3 },
};

// Stable accessors (module-level so react-globe.gl never sees a "changed" prop).
const hexColor = (f: object) => {
  const iso = (f as { properties?: { iso?: string } }).properties?.iso;
  return iso === "DE" ? "rgba(255,196,120,0.95)" : "rgba(130,165,240,0.62)";
};
const arcStartLat = (o: object) => (o as ArcDatum).s.origin.lat;
const arcStartLng = (o: object) => (o as ArcDatum).s.origin.lon;
const arcEndLat = (o: object) => (o as ArcDatum).s.destination.lat;
const arcEndLng = (o: object) => (o as ArcDatum).s.destination.lon;
const arcDashLength = (o: object) => ((o as ArcDatum).kind === "flow" ? 0.28 : 1);
const arcDashGap = (o: object) => ((o as ArcDatum).kind === "flow" ? 0.72 : 0);
const arcDashInitialGap = (o: object) =>
  (o as ArcDatum).kind === "flow" ? ((o as ArcDatum).s.bookingId * 0.137) % 1 : 0;
const arcDashAnimateTime = (o: object) => ((o as ArcDatum).kind === "flow" ? 2800 : 0);
const arcLabel = (o: object) => tooltipHtml((o as ArcDatum).s);
const planeLabel = (o: object) => tooltipHtml(o as ActiveShipment);
const ringColor = () => (t: number) => `rgba(255,181,71,${(1 - t) * 0.9})`;

const GLOBE_CSS = `
.fwd-globe { cursor: grab; }
.fwd-globe:active { cursor: grabbing; }
.fwd-globe .float-tooltip-kap {
  background: rgba(8, 13, 28, 0.82) !important;
  backdrop-filter: blur(14px) saturate(140%);
  -webkit-backdrop-filter: blur(14px) saturate(140%);
  border: 1px solid rgba(148, 170, 230, 0.18);
  border-radius: 12px !important;
  padding: 12px 14px !important;
  box-shadow: 0 18px 50px -12px rgba(0,0,0,0.7), inset 0 1px 0 rgba(255,255,255,0.04);
  color: #e6ecff !important;
  font: 12px/1.45 var(--font-geist-sans), ui-sans-serif, system-ui, sans-serif !important;
  max-width: 300px !important;
}
.fwd-tt-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:8px; }
.fwd-tt-ref { font-family: var(--font-geist-mono), ui-monospace, monospace; font-size: 11px; letter-spacing: .04em; color: #9fb0d9; }
.fwd-tt-chip { font-size: 10px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; padding: 2px 7px; border-radius: 999px; border: 1px solid; }
.fwd-tt-route { display:flex; align-items:baseline; gap:6px; font-size: 15px; margin-bottom: 10px; white-space: nowrap; }
.fwd-tt-route b { font-weight: 650; letter-spacing: .02em; color: #fff; }
.fwd-tt-city { font-size: 11px; color: #8a9ac4; }
.fwd-tt-arrow { color: #5f6f99; margin: 0 2px; }
.fwd-tt-grid { display:grid; grid-template-columns: auto 1fr; gap: 3px 14px; margin: 0; }
.fwd-tt-grid dt { color: #7383ad; }
.fwd-tt-grid dd { margin: 0; color: #dfe6ff; text-align: right; }
.fwd-tt-grid .mono { font-family: var(--font-geist-mono), ui-monospace, monospace; font-size: 11px; }

.fwd-ap { position: relative; width: 0; height: 0; pointer-events: none; }
.fwd-ap-dot {
  position: absolute; left: -3px; top: -3px; width: 6px; height: 6px; border-radius: 999px;
  background: #cfe0ff; box-shadow: 0 0 0 2px rgba(120,160,255,0.25), 0 0 10px rgba(140,180,255,0.9);
}
.fwd-ap-hub .fwd-ap-dot { background: #ffd08a; box-shadow: 0 0 0 2px rgba(255,181,71,0.3), 0 0 12px rgba(255,181,71,0.95); }
.fwd-ap-label {
  position: absolute; left: 8px; top: -8px; white-space: nowrap; display: flex; gap: 5px; align-items: baseline;
  font: 600 10.5px/1 var(--font-geist-sans), ui-sans-serif, system-ui, sans-serif; letter-spacing: .06em;
  color: #e9efff; text-shadow: 0 1px 6px rgba(0,0,0,0.9), 0 0 2px rgba(0,0,0,0.9);
}
.fwd-ap-label i { font-style: normal; font-weight: 450; letter-spacing: .01em; color: #8f9fc8; font-size: 10px; }
.fwd-ap-hub .fwd-ap-label { color: #ffe2b8; }
.fwd-ap-l .fwd-ap-label { left: auto; right: 8px; flex-direction: row-reverse; }
.fwd-ap-hub .fwd-ap-label i { display: none; }
`;

const GlobeCanvas = memo(GlobeCanvasImpl);
export default GlobeCanvas;
