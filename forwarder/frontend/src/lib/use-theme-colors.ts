"use client";

import { useEffect, useState } from "react";
import { useTheme } from "./theme";

const TOKENS = ["accent", "ok", "attn", "danger", "violet", "cyan", "pink", "fg-muted", "fg-subtle", "line", "surface", "surface-2", "fg"] as const;
export type ThemeColors = Record<(typeof TOKENS)[number], string>;

const FALLBACK: ThemeColors = {
  accent: "#7cb4ff",
  ok: "#4fd1a1",
  attn: "#f5b547",
  danger: "#f2716e",
  violet: "#b39dfa",
  cyan: "#5ccfe6",
  pink: "#f08fc0",
  "fg-muted": "#98a1b0",
  "fg-subtle": "#5f6878",
  line: "#1d232d",
  surface: "#0e1116",
  "surface-2": "#13171e",
  fg: "#e6e9ee",
};

/** SVG attributes can't read CSS variables, so charts get resolved colours that follow the theme. */
export function useThemeColors(): ThemeColors {
  const { preference } = useTheme();
  const [colors, setColors] = useState<ThemeColors>(FALLBACK);
  useEffect(() => {
    const read = () => {
      const cs = getComputedStyle(document.documentElement);
      setColors(Object.fromEntries(TOKENS.map((t) => [t, cs.getPropertyValue(`--${t}`).trim() || FALLBACK[t]])) as ThemeColors);
    };
    read();
    const mo = new MutationObserver(read);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => mo.disconnect();
  }, [preference]);
  return colors;
}
