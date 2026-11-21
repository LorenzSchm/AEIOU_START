"use client";

import type { ReactNode } from "react";
import { useGetStats } from "@/api/generated/stats/stats";
import type { Stats } from "@/api/generated/model";
import { cn } from "@/lib/cn";
import { clock, usd } from "@/lib/format";
import { Skeleton } from "@/components/ui/states";

function Tile({
  label,
  value,
  hint,
  tone,
  meter,
}: {
  label: string;
  value: ReactNode;
  /** Shown on hover only. */
  hint?: string;
  tone?: "ok" | "attn" | "danger";
  /** 0..1 against the scoring target. */
  meter?: { value: number; good: boolean };
}) {
  return (
    <div className="relative flex min-w-[120px] flex-1 flex-col gap-0.5 px-4 py-3" title={hint}>
      <span className="eyebrow">{label}</span>
      <span
        className={cn(
          "num text-2xl leading-8 font-semibold",
          tone === "ok" && "text-ok",
          tone === "attn" && "text-attn",
          tone === "danger" && "text-danger",
        )}
      >
        {value}
      </span>
      {meter && (
        <span className="absolute right-4 bottom-2 left-4 h-[3px] overflow-hidden rounded-full bg-surface-3">
          <span
            className={cn("block h-full rounded-full", meter.good ? "bg-ok" : "bg-attn")}
            style={{ width: `${Math.min(100, Math.max(4, meter.value * 100))}%` }}
          />
        </span>
      )}
    </div>
  );
}

function openRfqs(s: Stats): number {
  return s.statusCounts.NEW + s.statusCounts.PRICING + s.statusCounts.WAITING_FOR_BIDS;
}

/** Five numbers that matter for the board: load, output, hit rate, speed, cost. */
export function KpiStrip() {
  const { data: s, isPending } = useGetStats({ hours: 24 });

  if (isPending || !s) {
    return (
      <div className="panel flex divide-x divide-line overflow-hidden">
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i} className="flex flex-1 flex-col gap-2 px-4 py-3">
            <Skeleton className="h-3 w-16" />
            <Skeleton className="h-7 w-12" />
          </div>
        ))}
      </div>
    );
  }

  const cost = Number(s.llmCostUsdPerQuote);
  const decided = s.accepted + s.rejected;
  const median = s.medianResponseSeconds;

  return (
    <div className="panel flex flex-wrap divide-line overflow-hidden sm:divide-x">
      <Tile label="Open" value={openRfqs(s)} hint={`${s.rfqsTotal} RFQs in 24 h`} />
      <Tile label="Quoted" value={s.quotesSent} hint={`${s.booked} booked`} />
      <Tile
        label="Accepted"
        value={decided ? `${Math.round((s.accepted / decided) * 100)}%` : "—"}
        tone={decided ? (s.accepted / decided >= 0.8 ? "ok" : "attn") : undefined}
        hint={`${s.accepted} accepted · ${s.rejected} rejected`}
      />
      <Tile
        label="Response"
        value={median != null ? clock(median) : "—"}
        tone={median != null ? (median <= 120 ? "ok" : median > 300 ? "danger" : "attn") : undefined}
        hint={s.p90ResponseSeconds != null ? `median · p90 ${clock(s.p90ResponseSeconds)} · target 2:00` : "median · target 2:00"}
        meter={median != null ? { value: median / 360, good: median <= 120 } : undefined}
      />
      <Tile
        label="LLM / quote"
        value={usd(cost)}
        hint="Full points at $0, none at $0.10"
        meter={{ value: cost / 0.1, good: cost < 0.02 }}
      />
    </div>
  );
}
