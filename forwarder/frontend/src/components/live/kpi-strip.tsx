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
  sub,
  tone,
  meter,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  tone?: "attn" | "ok" | "danger";
  /** 0..1 against the scoring target, drawn as a hairline gauge. */
  meter?: { value: number; good: boolean };
}) {
  return (
    <div
      className={cn(
        "relative flex min-w-[148px] flex-1 flex-col gap-1 overflow-hidden bg-surface px-4 py-3",
        tone === "attn" && "bg-attn/[0.07]",
      )}
    >
      {tone === "attn" && <span className="absolute inset-y-0 left-0 w-0.5 bg-attn" />}
      <span className="eyebrow">{label}</span>
      <span
        className={cn(
          "num text-[22px] leading-7 font-semibold",
          tone === "attn" && "text-attn",
          tone === "ok" && "text-ok",
          tone === "danger" && "text-danger",
        )}
      >
        {value}
      </span>
      <span className="truncate text-2xs text-subtle">{sub ?? " "}</span>
      {meter && (
        <span className="absolute right-4 bottom-0 left-4 h-0.5 overflow-hidden rounded-full bg-surface-3">
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

export function KpiStrip() {
  const { data: s, isPending } = useGetStats({ hours: 24 });

  if (isPending || !s) {
    return (
      <div className="panel grid grid-cols-2 gap-px overflow-hidden sm:grid-cols-4 xl:grid-cols-7">
        {Array.from({ length: 7 }, (_, i) => (
          <div key={i} className="flex flex-col gap-2 bg-surface px-4 py-3">
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-6 w-14" />
            <Skeleton className="h-3 w-24" />
          </div>
        ))}
      </div>
    );
  }

  const costPerQuote = Number(s.llmCostUsdPerQuote);
  const decided = s.accepted + s.rejected;
  const median = s.medianResponseSeconds;

  return (
    <div className="panel flex flex-wrap gap-px overflow-hidden bg-line">
      <Tile label="Open RFQs" value={openRfqs(s)} sub={`${s.rfqsTotal} received in 24 h`} />
      <Tile label="Quotes sent" value={s.quotesSent} sub={`${s.rfqsTotal ? Math.round((s.quotesSent / s.rfqsTotal) * 100) : 0}% of RFQs`} />
      <Tile
        label="Accepted"
        value={s.accepted}
        tone={s.accepted > 0 ? "ok" : undefined}
        sub={decided ? `${s.rejected} rejected · ${Math.round((s.accepted / decided) * 100)}% hit rate` : "no board verdicts yet"}
      />
      <Tile label="Booked" value={s.booked} sub={`${s.statusCounts.CONFIRMED} confirmed by airline`} />
      <Tile
        label="Needs human"
        value={s.needsHuman}
        tone={s.needsHuman > 0 ? "attn" : undefined}
        sub={s.needsHuman > 0 ? "waiting in the queue below" : "all clear"}
      />
      <Tile
        label="LLM $ / quote"
        value={usd(costPerQuote)}
        sub="full points at $0, zero at $0.10"
        meter={{ value: costPerQuote / 0.1, good: costPerQuote < 0.02 }}
      />
      <Tile
        label="Median response"
        value={median != null ? clock(median) : "—"}
        tone={median != null ? (median <= 120 ? "ok" : median > 300 ? "danger" : "attn") : undefined}
        sub={s.p90ResponseSeconds != null ? `p90 ${clock(s.p90ResponseSeconds)} · target 2:00` : "target 2:00"}
        meter={median != null ? { value: median / 360, good: median <= 120 } : undefined}
      />
    </div>
  );
}
