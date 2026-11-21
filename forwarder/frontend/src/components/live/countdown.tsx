"use client";

import { parseISO } from "date-fns";
import type { SessionSummary } from "@/api/generated/model";
import { cn } from "@/lib/cn";
import { clock } from "@/lib/format";
import { useNow } from "@/lib/use-now";

/** Scoring: full time points within 2 min of the request, zero at 6 min. */
const FULL_POINTS_S = 120;

type CountdownSession = Pick<SessionSummary, "deadlineAt" | "quotedAt" | "createdAt" | "status" | "type">;

const TERMINAL = new Set(["EXPIRED", "FAILED", "CLOSED", "ANSWERED"]);

/**
 * Before the quote: time left until deadlineAt (amber under 2 min, red under 1 min).
 * After the quote: how long the quote took (green within 2 min).
 */
export function Countdown({ session, className, showLabel }: { session: CountdownSession; className?: string; showLabel?: boolean }) {
  const now = useNow();
  const created = parseISO(session.createdAt).getTime();

  if (session.quotedAt) {
    const took = (parseISO(session.quotedAt).getTime() - created) / 1000;
    return (
      <span
        className={cn("num inline-flex items-center gap-1 text-xs", took <= FULL_POINTS_S ? "text-ok" : "text-attn", className)}
        title={`Quoted ${clock(took)} after the request`}
      >
        {showLabel && <span className="font-sans text-subtle">quoted in</span>}
        {clock(took)}
      </span>
    );
  }

  if (!session.deadlineAt || TERMINAL.has(session.status)) {
    return <span className={cn("num text-xs text-subtle", className)}>{session.status === "EXPIRED" ? "expired" : "—"}</span>;
  }
  if (now === 0) return <span className={cn("num text-xs text-subtle", className)}>–:––</span>;

  const left = (parseISO(session.deadlineAt).getTime() - now) / 1000;
  if (left <= 0) {
    return <span className={cn("num text-xs font-medium text-danger", className)}>overdue</span>;
  }
  const tone = left < 60 ? "text-danger" : left < 120 ? "text-attn" : "text-fg";
  return (
    <span className={cn("num inline-flex items-center gap-1.5 text-xs font-medium", tone, className)} title="Time left until the RFQ expires">
      {showLabel && <span className="font-sans font-normal text-subtle">expires in</span>}
      <span className={cn("size-1.5 rounded-full", left < 60 ? "animate-pulse-dot bg-danger" : left < 120 ? "bg-attn" : "bg-ok")} />
      {clock(left)}
    </span>
  );
}

/** Thin progress bar of the 6-minute window, with the 2-minute mark drawn in. */
export function DeadlineBar({ session }: { session: CountdownSession }) {
  const now = useNow();
  if (!session.deadlineAt || now === 0) return null;
  const start = parseISO(session.createdAt).getTime();
  const end = parseISO(session.deadlineAt).getTime();
  const at = session.quotedAt ? parseISO(session.quotedAt).getTime() : now;
  const total = Math.max(1, end - start);
  const progress = Math.min(1, Math.max(0, (at - start) / total));
  const markPct = Math.min(100, ((FULL_POINTS_S * 1000) / total) * 100);
  const tone = session.quotedAt
    ? at - start <= FULL_POINTS_S * 1000
      ? "bg-ok"
      : "bg-attn"
    : end - now < 60_000
      ? "bg-danger"
      : end - now < 120_000
        ? "bg-attn"
        : "bg-accent";
  return (
    <div className="relative h-1 w-full overflow-hidden rounded-full bg-surface-3" aria-hidden>
      <div className={cn("h-full rounded-full transition-[width] duration-1000 ease-linear", tone)} style={{ width: `${progress * 100}%` }} />
      <div className="absolute top-0 h-full w-px bg-fg/40" style={{ left: `${markPct}%` }} />
    </div>
  );
}
