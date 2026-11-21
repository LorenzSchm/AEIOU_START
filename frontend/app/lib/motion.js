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

// Disclosure (expand/collapse). Content fades first, then height settles with an iOS-like curve,
// so nothing visibly "snaps" shut. Shared by sections and emails.
const iosEase = [0.32, 0.72, 0, 1];
export const disclosure = {
  initial: { height: 0, opacity: 0 },
  animate: {
    height: "auto",
    opacity: 1,
    transition: { height: { type: "spring", stiffness: 320, damping: 34 }, opacity: { duration: 0.2, delay: 0.05 } },
  },
  exit: {
    height: 0,
    opacity: 0,
    transition: { height: { duration: 0.3, ease: iosEase }, opacity: { duration: 0.14, ease: "easeOut" } },
  },
};
export const previewSwap = (shown) => ({
  height: shown ? "auto" : 0,
  opacity: shown ? 1 : 0,
  transition: shown
    ? { height: { duration: 0.3, ease: iosEase }, opacity: { duration: 0.2, delay: 0.12 } }
    : { height: { duration: 0.24, ease: iosEase }, opacity: { duration: 0.1 } },
});
