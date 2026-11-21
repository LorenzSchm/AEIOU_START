import * as THREE from "three";
import { arcAltitude, greatCirclePoint } from "./flight";
import type { ActiveShipment } from "./types";

export const GLOBE_RADIUS = 100;
const DEG = Math.PI / 180;

/** Same projection as three-globe's polar2Cartesian, so our objects line up with its layers. */
export function toVec(lat: number, lng: number, alt = 0, target = new THREE.Vector3()): THREE.Vector3 {
  const phi = (90 - lat) * DEG;
  const theta = (90 - lng) * DEG;
  const r = GLOBE_RADIUS * (1 + alt);
  const s = Math.sin(phi);
  return target.set(r * s * Math.cos(theta), r * Math.cos(phi), r * s * Math.sin(theta));
}

export function toLatLng(v: THREE.Vector3): { lat: number; lng: number } {
  const r = v.length();
  const phi = Math.acos(v.y / r);
  const theta = Math.atan2(v.z, v.x);
  let lng = 90 - theta / DEG;
  if (lng > 180) lng -= 360;
  return { lat: 90 - phi / DEG, lng };
}

/**
 * Rebuilds three-globe's arc curve (cubic Bézier through the great circle with lifted control
 * points) so the plane rides exactly on the rendered arc.
 */
export function buildArcCurve(s: ActiveShipment): THREE.CubicBezierCurve3 {
  const { lat: aLat, lon: aLon } = s.origin;
  const { lat: bLat, lon: bLon } = s.destination;
  const alt = arcAltitude(s);
  const cp = (a0: number, a1: number) => a1 + (a1 - a0) * (a0 < a1 ? 0.5 : 0.25);
  const [m1Lat, m1Lon] = greatCirclePoint(aLat, aLon, bLat, bLon, 0.25);
  const [m2Lat, m2Lon] = greatCirclePoint(aLat, aLon, bLat, bLon, 0.75);
  return new THREE.CubicBezierCurve3(
    toVec(aLat, aLon, 0),
    toVec(m1Lat, m1Lon, cp(0, alt)),
    toVec(m2Lat, m2Lon, cp(0, alt)),
    toVec(bLat, bLon, 0),
  );
}

/** Stylised freighter silhouette, nose along +Z, belly along -Y. Extruded so it has volume. */
export function createPlaneGeometry(): THREE.BufferGeometry {
  const s = new THREE.Shape();
  // Outline in (x = span, y = forward). Built for the right half, then mirrored.
  const half: [number, number][] = [
    [0, 1.25],
    [0.07, 1.16],
    [0.11, 0.98],
    [0.12, 0.42],
    [1.05, -0.05],
    [1.08, -0.2],
    [0.12, 0.02],
    [0.1, -0.72],
    [0.44, -0.98],
    [0.45, -1.08],
    [0.06, -0.96],
    [0, -1.02],
  ];
  const pts = [...half, ...half.slice(1, -1).reverse().map(([x, y]) => [-x, y] as [number, number])];
  s.moveTo(pts[0][0], pts[0][1]);
  for (let i = 1; i < pts.length; i++) s.lineTo(pts[i][0], pts[i][1]);
  s.closePath();
  const g = new THREE.ExtrudeGeometry(s, {
    depth: 0.1,
    bevelEnabled: true,
    bevelThickness: 0.04,
    bevelSize: 0.03,
    bevelSegments: 1,
  });
  g.rotateX(Math.PI / 2); // forward (y) -> +z, thickness (z) -> -y
  g.translate(0, 0.07, 0);
  g.computeVertexNormals();
  return g;
}

/** Soft radial glow texture generated on a canvas (no external asset). */
export function createGlowTexture(): THREE.Texture {
  const size = 128;
  const c = document.createElement("canvas");
  c.width = c.height = size;
  const ctx = c.getContext("2d")!;
  const g = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  g.addColorStop(0, "rgba(255,255,255,1)");
  g.addColorStop(0.18, "rgba(255,255,255,0.65)");
  g.addColorStop(0.45, "rgba(255,255,255,0.18)");
  g.addColorStop(1, "rgba(255,255,255,0)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, size, size);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/** Faint, slightly coloured starfield far behind the globe. */
export function createStarfield(count = 2200): THREE.Points {
  const pos = new Float32Array(count * 3);
  const col = new Float32Array(count * 3);
  const v = new THREE.Vector3();
  const tint = [new THREE.Color("#ffffff"), new THREE.Color("#9fb7ff"), new THREE.Color("#ffd9b0")];
  for (let i = 0; i < count; i++) {
    v.randomDirection().multiplyScalar(1800 + Math.random() * 1600);
    pos.set([v.x, v.y, v.z], i * 3);
    const c = tint[i % 7 === 0 ? 2 : i % 3 === 0 ? 1 : 0].clone().multiplyScalar(0.35 + Math.random() * 0.65);
    col.set([c.r, c.g, c.b], i * 3);
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  geo.setAttribute("color", new THREE.BufferAttribute(col, 3));
  const mat = new THREE.PointsMaterial({
    size: 1.6,
    sizeAttenuation: false,
    vertexColors: true,
    transparent: true,
    opacity: 0.85,
    depthWrite: false,
  });
  const pts = new THREE.Points(geo, mat);
  pts.name = "forwarder-stars";
  return pts;
}

export function disposeObject(root: THREE.Object3D) {
  root.traverse((o) => {
    const m = o as THREE.Mesh;
    m.geometry?.dispose?.();
    const mat = m.material as THREE.Material | THREE.Material[] | undefined;
    if (Array.isArray(mat)) mat.forEach((x) => x.dispose());
    else mat?.dispose?.();
  });
}
