// Shared Motion presets so every animation has the same rhythm (Apple-like springs).
export const spring = { type: "spring", stiffness: 380, damping: 34, mass: 0.9 };
export const softSpring = { type: "spring", stiffness: 220, damping: 28 };
export const fadeUp = {
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: 4, transition: { duration: 0.14 } },
  transition: spring,
};
export const stagger = (i, step = 0.04) => ({ ...spring, delay: Math.min(i, 12) * step });
