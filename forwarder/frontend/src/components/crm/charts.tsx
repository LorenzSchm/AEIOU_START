"use client";

import { format, parseISO } from "date-fns";
import type { ReactNode } from "react";
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { BoardResult, StatsHour, SessionSummary } from "@/api/generated/model";
import { useThemeColors, type ThemeColors } from "@/lib/use-theme-colors";

function axis(c: ThemeColors) {
  return {
    stroke: c.line,
    tick: { fill: c["fg-subtle"], fontSize: 11, fontFamily: "var(--font-plex-mono)" },
    tickLine: false,
    axisLine: false,
  } as const;
}

function ChartTooltip({ active, payload, label, fmt }: { active?: boolean; payload?: { name?: string; value?: number | string; color?: string }[]; label?: ReactNode; fmt?: (v: number) => string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="panel px-3 py-2 text-xs">
      <p className="num mb-1 text-subtle">{label}</p>
      {payload.map((p) => (
        <p key={p.name} className="flex items-center gap-2">
          <span className="size-2 rounded-sm" style={{ background: p.color }} />
          <span className="text-muted">{p.name}</span>
          <span className="num ml-auto font-medium">{fmt && typeof p.value === "number" ? fmt(p.value) : p.value}</span>
        </p>
      ))}
    </div>
  );
}

function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <div className="flex flex-wrap gap-3 text-2xs text-muted">
      {items.map((i) => (
        <span key={i.label} className="flex items-center gap-1.5">
          <span className="size-2 rounded-sm" style={{ background: i.color }} />
          {i.label}
        </span>
      ))}
    </div>
  );
}

const hourLabel = (h: string) => format(parseISO(h), "HH:mm");

export function QuotesPerHour({ hourly }: { hourly: StatsHour[] }) {
  const c = useThemeColors();
  const data = hourly.slice(-12).map((h) => ({ hour: hourLabel(h.hour), Quotes: h.quotes, Accepted: h.accepted, Rejected: h.rejected }));
  return (
    <div className="flex flex-col gap-2">
      <Legend items={[{ label: "Quotes", color: c.accent }, { label: "Accepted", color: c.ok }, { label: "Rejected", color: c.danger }]} />
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} barGap={2} margin={{ top: 4, right: 4, left: -24, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke={c.line} />
          <XAxis dataKey="hour" {...axis(c)} interval="preserveStartEnd" />
          <YAxis allowDecimals={false} {...axis(c)} />
          <Tooltip cursor={{ fill: c["surface-2"] }} content={<ChartTooltip />} />
          <Bar dataKey="Quotes" fill={c.accent} radius={[3, 3, 0, 0]} maxBarSize={14} />
          <Bar dataKey="Accepted" fill={c.ok} radius={[3, 3, 0, 0]} maxBarSize={14} />
          <Bar dataKey="Rejected" fill={c.danger} radius={[3, 3, 0, 0]} maxBarSize={14} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function CostPerHour({ hourly }: { hourly: StatsHour[] }) {
  const c = useThemeColors();
  const data = hourly.slice(-12).map((h) => ({ hour: hourLabel(h.hour), Cost: Number(h.llmCostUsd), perQuote: h.quotes ? Number(h.llmCostUsd) / h.quotes : 0 }));
  return (
    <div className="flex flex-col gap-2">
      <Legend items={[{ label: "LLM spend per hour (USD)", color: c.violet }]} />
      <ResponsiveContainer width="100%" height={200}>
        <AreaChart data={data} margin={{ top: 4, right: 4, left: -12, bottom: 0 }}>
          <defs>
            <linearGradient id="cost-fill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={c.violet} stopOpacity={0.35} />
              <stop offset="100%" stopColor={c.violet} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke={c.line} />
          <XAxis dataKey="hour" {...axis(c)} interval="preserveStartEnd" />
          <YAxis {...axis(c)} tickFormatter={(v: number) => `$${v.toFixed(3)}`} />
          <Tooltip content={<ChartTooltip fmt={(v) => `$${v.toFixed(5)}`} />} />
          <Area type="monotone" dataKey="Cost" stroke={c.violet} strokeWidth={1.75} fill="url(#cost-fill)" />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

const BUCKETS: [number, number, string][] = [
  [0, 30, "<0:30"],
  [30, 60, "0:30"],
  [60, 90, "1:00"],
  [90, 120, "1:30"],
  [120, 180, "2:00"],
  [180, 240, "3:00"],
  [240, 360, "4:00"],
  [360, Infinity, "6:00+"],
];

export function ResponseDistribution({ sessions }: { sessions: SessionSummary[] }) {
  const c = useThemeColors();
  const seconds = sessions
    .filter((s) => s.quotedAt)
    .map((s) => (parseISO(s.quotedAt!).getTime() - parseISO(s.createdAt).getTime()) / 1000);
  const data = BUCKETS.map(([a, b, label]) => ({ label, from: a, Quotes: seconds.filter((x) => x >= a && x < b).length }));
  return (
    <div className="flex flex-col gap-2">
      <Legend items={[{ label: "within 2:00 (full points)", color: c.ok }, { label: "2:00–6:00", color: c.attn }, { label: "late", color: c.danger }]} />
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} margin={{ top: 4, right: 4, left: -24, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke={c.line} />
          <XAxis dataKey="label" {...axis(c)} />
          <YAxis allowDecimals={false} {...axis(c)} />
          <Tooltip cursor={{ fill: c["surface-2"] }} content={<ChartTooltip />} />
          <Bar dataKey="Quotes" radius={[3, 3, 0, 0]} maxBarSize={36}>
            {data.map((d) => (
              <Cell key={d.label} fill={d.from < 120 ? c.ok : d.from < 360 ? c.attn : c.danger} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function ReasonCodes({ board }: { board: BoardResult[] }) {
  const counts = new Map<string, number>();
  for (const b of board) {
    if (b.verdict !== "rejected") continue;
    const k = b.reasonCode ?? "UNKNOWN";
    counts.set(k, (counts.get(k) ?? 0) + 1);
  }
  const rows = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  const max = Math.max(1, ...rows.map((r) => r[1]));
  if (rows.length === 0) return <p className="py-6 text-center text-xs text-subtle">No rejections yet. Reason codes show up here first when the setup changes.</p>;
  return (
    <ul className="flex flex-col gap-2">
      {rows.map(([code, n]) => (
        <li key={code} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1">
          <span className="num truncate text-xs">{code}</span>
          <span className="num text-xs text-danger">{n}</span>
          <span className="col-span-2 h-1 overflow-hidden rounded-full bg-surface-3">
            <span className="block h-full rounded-full bg-danger" style={{ width: `${(n / max) * 100}%` }} />
          </span>
        </li>
      ))}
    </ul>
  );
}
