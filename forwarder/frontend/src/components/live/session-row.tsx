"use client";

import { Hand, PlaneTakeoff } from "lucide-react";
import Link from "next/link";
import { forwardRef } from "react";
import type { SessionSummary } from "@/api/generated/model";
import { ModeTag, StatusChip, TypeTag, VerdictChip } from "@/components/session/status";
import { cn } from "@/lib/cn";
import { addressName, ago, money, usd } from "@/lib/format";
import { useNow } from "@/lib/use-now";
import { Countdown } from "./countdown";

export const SESSION_GRID =
  "grid grid-cols-[112px_minmax(0,1fr)_auto] md:grid-cols-[120px_minmax(0,1.5fr)_104px_80px_96px_64px_minmax(0,1fr)] items-center gap-x-4";

export function SessionHeaderRow() {
  return (
    <div className={cn(SESSION_GRID, "eyebrow sticky top-0 z-10 hidden border-b border-line bg-surface px-4 py-2 md:grid")}>
      <span>Status</span>
      <span>Session</span>
      <span>Lane</span>
      <span>Clock</span>
      <span className="text-right">Quoted</span>
      <span className="text-right">LLM</span>
      <span>Last event</span>
    </div>
  );
}

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
      className={cn(
        SESSION_GRID,
        "group relative border-b border-line px-4 py-2.5 text-[13px] transition-colors last:border-b-0 hover:bg-surface-2",
        selected && "bg-surface-2 ring-1 ring-accent/40 ring-inset",
        s.needsHuman && "bg-attn/[0.04]",
      )}
    >
      {/* Re-mounts on every update, so a changed row flashes once. */}
      {now > 0 && now - Date.parse(s.lastEventAt) < 3000 && (
        <span key={s.lastEventAt} className="pointer-events-none absolute inset-0 animate-flash" aria-hidden />
      )}
      {s.needsHuman && <span className="absolute inset-y-0 left-0 w-0.5 bg-attn" aria-hidden />}

      <div className="flex flex-col items-start gap-1">
        <StatusChip status={s.status} />
        <TypeTag type={s.type} />
      </div>

      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="num shrink-0 text-xs font-semibold whitespace-nowrap text-accent">{s.publicRef}</span>
          {!s.autopilot && (
            <span title="A human has taken over" className="text-attn">
              <Hand className="size-3" />
            </span>
          )}
          {s.booked && (
            <span title="Booked" className="text-ok">
              <PlaneTakeoff className="size-3" />
            </span>
          )}
          <span className="truncate text-muted">{addressName(s.customerAddr)}</span>
        </div>
        <p className="mt-0.5 truncate text-fg/90">{s.subject ?? "—"}</p>
        {/* Compact meta for narrow screens */}
        <p className="mt-0.5 truncate text-xs text-subtle md:hidden">
          {s.lane ?? ""} {s.mode ? `· ${s.mode}` : ""} {s.quotedTotal ? `· ${money(s.quotedTotal, s.quotedCurrency)}` : ""}
        </p>
      </div>

      <div className="hidden min-w-0 flex-col md:flex">
        <span className="num truncate text-xs">{s.lane ?? "—"}</span>
        <span className="text-2xs">
          <ModeTag mode={s.mode} />
        </span>
      </div>

      <div className="flex flex-col items-end gap-1 md:items-start">
        <Countdown session={s} />
        <span className="md:hidden">
          <VerdictChip verdict={s.boardVerdict} />
        </span>
      </div>

      <div className="hidden flex-col items-end md:flex">
        <span className={cn("num text-xs", s.quotedTotal ? "text-fg" : "text-subtle")}>{money(s.quotedTotal, s.quotedCurrency)}</span>
        {s.boardVerdict && <VerdictChip verdict={s.boardVerdict} />}
      </div>

      <span className="num hidden text-right text-xs text-muted md:block">{usd(s.llmCostUsd)}</span>

      <div className="hidden min-w-0 items-baseline gap-2 md:flex">
        <span className="truncate text-xs text-muted">{s.needsHuman && s.needsHumanReason ? <span className="text-attn">{s.needsHumanReason}</span> : s.lastEventSummary}</span>
        <span className="num ml-auto shrink-0 text-2xs text-subtle">{ago(s.lastEventAt, now)}</span>
      </div>
    </Link>
  );
});
