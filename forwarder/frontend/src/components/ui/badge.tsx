import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export type Tone = "neutral" | "accent" | "ok" | "attn" | "danger" | "violet" | "cyan" | "pink" | "muted";

export const TONE_TEXT: Record<Tone, string> = {
  neutral: "text-fg",
  accent: "text-accent",
  ok: "text-ok",
  attn: "text-attn",
  danger: "text-danger",
  violet: "text-violet",
  cyan: "text-cyan",
  pink: "text-pink",
  muted: "text-subtle",
};

export const TONE_BG: Record<Tone, string> = {
  neutral: "bg-fg",
  accent: "bg-accent",
  ok: "bg-ok",
  attn: "bg-attn",
  danger: "bg-danger",
  violet: "bg-violet",
  cyan: "bg-cyan",
  pink: "bg-pink",
  muted: "bg-subtle",
};

const TONE_CHIP: Record<Tone, string> = {
  neutral: "bg-surface-3 text-fg border-line-strong",
  accent: "bg-accent/10 text-accent border-accent/25",
  ok: "bg-ok/10 text-ok border-ok/25",
  attn: "bg-attn/12 text-attn border-attn/30",
  danger: "bg-danger/10 text-danger border-danger/25",
  violet: "bg-violet/10 text-violet border-violet/25",
  cyan: "bg-cyan/10 text-cyan border-cyan/25",
  pink: "bg-pink/10 text-pink border-pink/25",
  muted: "bg-surface-2 text-subtle border-line",
};

export function Badge({
  tone = "neutral",
  dot,
  pulse,
  className,
  children,
  title,
}: {
  tone?: Tone;
  dot?: boolean;
  pulse?: boolean;
  className?: string;
  children: ReactNode;
  title?: string;
}) {
  return (
    <span
      title={title}
      className={cn(
        "inline-flex h-5 shrink-0 items-center gap-1.5 rounded-[5px] border px-1.5 text-2xs font-medium whitespace-nowrap",
        TONE_CHIP[tone],
        className,
      )}
    >
      {dot && <span className={cn("size-1.5 rounded-full", TONE_BG[tone], pulse && "animate-pulse-dot")} />}
      {children}
    </span>
  );
}
