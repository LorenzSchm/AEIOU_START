import type { CSSProperties } from "react";

/**
 * The living backdrop: a daytime sky with drifting clouds and a night sky with stars, a moon and a
 * few faint clouds. Both are rendered once, behind everything, and cross-faded by the theme in CSS.
 * Pure CSS: clouds move with transform only, stars are box-shadows, so it stays cheap on the GPU.
 */

/** Deterministic pseudo-random, so server and client render the same sky. */
function rng(seed: number) {
  let s = seed;
  return () => {
    s = (s * 16807) % 2147483647;
    return (s - 1) / 2147483646;
  };
}

function starField(count: number, seed: number, maxSize: number, alpha: [number, number]): string {
  const r = rng(seed);
  const out: string[] = [];
  for (let i = 0; i < count; i++) {
    const x = (r() * 100).toFixed(2);
    // denser towards the top of the sky
    const y = (Math.pow(r(), 1.6) * 92).toFixed(2);
    const size = (r() * maxSize).toFixed(1);
    const a = (alpha[0] + r() * (alpha[1] - alpha[0])).toFixed(2);
    out.push(`${x}vw ${y}vh 0 ${size}px rgb(255 255 255 / ${a})`);
  }
  return out.join(",");
}

const STARS_DIM = starField(170, 7, 0.6, [0.25, 0.6]);
const STARS_BRIGHT = starField(48, 19, 1.2, [0.6, 1]);
const STARS_TWINKLE = starField(26, 41, 1.4, [0.7, 1]);

/** One cloud: a few overlapping puffs blurred into a soft shape. */
const PUFFS: [number, number, number, number][] = [
  // left%, top%, width%, height%
  [0, 42, 46, 58],
  [20, 12, 40, 88],
  [44, 0, 36, 100],
  [64, 26, 36, 74],
  [10, 55, 80, 45],
];

interface CloudSpec {
  top: string;
  w: number;
  dur: number;
  delay: number;
  o: number;
  blur?: number;
  rest: string;
}

const DAY_CLOUDS: CloudSpec[] = [
  { top: "2%", w: 460, dur: 210, delay: -40, o: 1, rest: "8vw" },
  { top: "14%", w: 320, dur: 170, delay: -120, o: 0.95, rest: "58vw" },
  { top: "26%", w: 560, dur: 260, delay: -10, o: 0.9, blur: 9, rest: "30vw" },
  { top: "40%", w: 380, dur: 200, delay: -165, o: 0.9, rest: "82vw" },
  { top: "50%", w: 420, dur: 190, delay: -95, o: 0.85, rest: "72vw" },
  { top: "64%", w: 680, dur: 300, delay: -200, o: 0.85, blur: 11, rest: "-4vw" },
  { top: "78%", w: 500, dur: 230, delay: -70, o: 0.9, blur: 10, rest: "46vw" },
  { top: "88%", w: 720, dur: 320, delay: -260, o: 0.95, blur: 12, rest: "20vw" },
];

const NIGHT_CLOUDS: CloudSpec[] = [
  { top: "22%", w: 520, dur: 320, delay: -90, o: 0.09, blur: 14, rest: "12vw" },
  { top: "58%", w: 640, dur: 380, delay: -230, o: 0.08, blur: 16, rest: "52vw" },
  { top: "78%", w: 480, dur: 300, delay: -30, o: 0.1, blur: 14, rest: "70vw" },
];

function Cloud({ spec, color }: { spec: CloudSpec; color: string }) {
  const style = {
    top: spec.top,
    "--dur": `${spec.dur}s`,
    "--delay": `${spec.delay}s`,
    "--rest": spec.rest,
  } as CSSProperties;
  const body = {
    "--w": `${spec.w}px`,
    "--o": spec.o,
    "--blur": `${spec.blur ?? 7}px`,
    "--cloud": color,
  } as CSSProperties;
  return (
    <div className="cloud" style={style}>
      <div className="cloud-body" style={body}>
        {PUFFS.map(([l, t, w, h], i) => (
          <span key={i} style={{ left: `${l}%`, top: `${t}%`, width: `${w}%`, height: `${h}%` }} />
        ))}
      </div>
    </div>
  );
}

export function Sky() {
  return (
    <div className="sky" aria-hidden>
      <div className="sky-layer sky-day">
        {DAY_CLOUDS.map((c, i) => (
          <Cloud key={i} spec={c} color="#ffffff" />
        ))}
      </div>
      <div className="sky-layer sky-night">
        <div className="stars" style={{ boxShadow: STARS_DIM }} />
        <div className="stars" style={{ boxShadow: STARS_BRIGHT }} />
        <div className="stars stars-twinkle" style={{ boxShadow: STARS_TWINKLE }} />
        <div className="moon" />
        <div className="shooting-star" />
        {NIGHT_CLOUDS.map((c, i) => (
          <Cloud key={i} spec={c} color="#b8c6ff" />
        ))}
      </div>
    </div>
  );
}
