"use client";

import { ArrowLeft, Bot, CircleCheck, Hand } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import type { SessionSummary } from "@/api/generated/model";
import { Countdown, DeadlineBar } from "@/components/live/countdown";
import { Button } from "@/components/ui/button";
import { addressName, money, usd } from "@/lib/format";
import { AttentionDot, ModeIcon, StatusChip, VerdictChip } from "./status";
import { useSessionActions } from "./use-session-action";

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="eyebrow">{label}</span>
      <span className="truncate text-[13px]">{children}</span>
    </div>
  );
}

export function SessionHeader({ s }: { s: SessionSummary }) {
  const { run, pending } = useSessionActions(s.id);

  return (
    <section className="panel overflow-hidden">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 pt-3.5">
        <Link href="/" className="rounded-md p-1 text-subtle hover:bg-surface-3 hover:text-fg" aria-label="Back">
          <ArrowLeft className="size-4" />
        </Link>
        <h1 className="num text-lg font-semibold tracking-tight">{s.publicRef}</h1>
        <StatusChip status={s.status} />
        {s.needsHuman && (
          <span className="inline-flex items-center gap-1.5 rounded-full border border-attn/30 bg-attn/10 px-2 py-0.5 text-2xs font-medium text-attn" title={s.needsHumanReason ?? undefined}>
            <AttentionDot /> Attention
          </span>
        )}
        <span className="min-w-0 flex-1 truncate text-[13px] text-muted" title={s.subject ?? undefined}>
          {addressName(s.customerAddr)}
        </span>
        <div className="flex items-center gap-1.5">
          {s.needsHuman && (
            <Button size="sm" variant="ghost" icon={<CircleCheck className="size-3.5" />} loading={pending === "resolve"} onClick={() => run({ action: "resolve" })}>
              Clear
            </Button>
          )}
          {s.autopilot ? (
            <Button size="sm" variant="secondary" icon={<Hand className="size-3.5" />} loading={pending === "take_over"} onClick={() => run({ action: "take_over" })}>
              Take over
            </Button>
          ) : (
            <Button size="sm" variant="secondary" icon={<Bot className="size-3.5" />} loading={pending === "release"} onClick={() => run({ action: "release" })}>
              Release
            </Button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-3 gap-x-6 gap-y-3 px-4 py-3 sm:grid-cols-6">
        <Stat label="Lane">
          <span className="num">{s.lane ?? "—"}</span>
        </Stat>
        <Stat label="Mode">
          <span className="inline-flex items-center gap-1.5">
            <ModeIcon mode={s.mode} />
            <span className="text-muted">{s.mode ?? "—"}</span>
          </span>
        </Stat>
        <Stat label={s.quotedAt ? "Quoted in" : "Expires"}>
          <Countdown session={s} />
        </Stat>
        <Stat label="Total">
          <span className="num font-semibold">{money(s.quotedTotal, s.quotedCurrency)}</span>
        </Stat>
        <Stat label="LLM">
          <span className="num text-muted">{usd(s.llmCostUsd)}</span>
        </Stat>
        <Stat label="Board">
          <VerdictChip verdict={s.boardVerdict} />
        </Stat>
      </div>
      {s.deadlineAt && (
        <div className="px-4 pb-3">
          <DeadlineBar session={s} />
        </div>
      )}
    </section>
  );
}
