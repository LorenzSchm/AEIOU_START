"use client";

import { Hand, PlaneTakeoff } from "lucide-react";
import Link from "next/link";
import { forwardRef } from "react";
import type { SessionSummary } from "@/api/generated/model";
import { AttentionDot, ModeIcon, StatusChip } from "@/components/session/status";
import { cn } from "@/lib/cn";
import { addressName, money } from "@/lib/format";
import { useNow } from "@/lib/use-now";
import { Countdown } from "./countdown";

export const SESSION_GRID =
  "grid grid-cols-[minmax(0,1fr)_auto] md:grid-cols-[96px_150px_20px_minmax(0,1fr)_80px_112px] items-center gap-x-4";

export function SessionHeaderRow() {
  return (
    <div className={cn(SESSION_GRID, "eyebrow glass-strong sticky top-0 z-10 hidden border-b border-line px-4 py-2 md:grid")}>
      <span>Status</span>
      <span>Ref</span>
      <span />
      <span>Lane</span>
      <span>Clock</span>
      <span className="text-right">Total</span>
    </div>
  );
}

/** One session, one line: status, ref, lane, mode, clock, total. Everything else lives on the detail page. */
export const SessionRow = forwardRef<HTMLAnchorElement, { s: SessionSummary; selected?: boolean }>(function SessionRow(
  { s, selected },
  ref,
) {
  const now = useNow();
  return (
    <Link
      ref={ref}
      href={`/sessions/${s.id}`}
      data-selected={selected || undefined}
      title={[addressName(s.customerAddr), s.subject].filter(Boolean).join(" · ")}
      className={cn(
        SESSION_GRID,
        "group relative h-11 border-b border-line px-4 text-[13px] transition-colors last:border-b-0 hover:bg-surface-2",
        selected && "bg-surface-2 ring-1 ring-accent/40 ring-inset",
      )}
    >
      {/* Re-mounts on every update, so a changed row flashes once. */}
      {now > 0 && now - Date.parse(s.lastEventAt) < 3000 && (
        <span key={s.lastEventAt} className="pointer-events-none absolute inset-0 animate-flash" aria-hidden />
      )}

      <span className="hidden md:block">
        <StatusChip status={s.status} />
      </span>

      <span className="flex min-w-0 items-center gap-2">
        <span className="num truncate text-xs font-semibold text-accent">{s.publicRef}</span>
        {s.needsHuman && <AttentionDot reason={s.needsHumanReason} />}
        {!s.autopilot && <Hand className="size-3 shrink-0 text-attn" aria-label="Taken over" />}
        {s.booked && <PlaneTakeoff className="size-3 shrink-0 text-ok" aria-label="Booked" />}
        <span className="num truncate text-xs text-muted md:hidden">{s.lane}</span>
      </span>

      <span className="hidden md:block">
        <ModeIcon mode={s.mode} />
      </span>
      <span className="num hidden truncate text-xs md:block">{s.lane ?? <span className="text-subtle">—</span>}</span>
      <span className="hidden md:block">
        <Countdown session={s} />
      </span>

      <span className="flex items-center justify-end gap-2">
        <span className="md:hidden">
          <Countdown session={s} />
        </span>
        <span className={cn("num text-xs", s.quotedTotal ? "font-medium text-fg" : "text-subtle")}>{money(s.quotedTotal, s.quotedCurrency)}</span>
      </span>
    </Link>
  );
});
