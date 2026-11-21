"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Check, Table2, TriangleAlert, X } from "lucide-react";
import { useMemo, useState } from "react";
import type { TariffDetail, TariffLane, TariffSummary } from "@/api/generated/model";
import { useActivateTariff, useGetTariff, useListTariffs, useRejectTariff } from "@/api/generated/tariffs/tariffs";
import { TariffChip } from "@/components/session/status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { SearchInput } from "@/components/ui/fields";
import { Panel } from "@/components/ui/panel";
import { EmptyState, ErrorState, Skeleton, SkeletonRows } from "@/components/ui/states";
import { useToast } from "@/components/ui/toast";
import { cn } from "@/lib/cn";
import { dateTime } from "@/lib/format";
import { byPath } from "@/lib/query-keys";

const breakLabel = (fromKg: number) => (fromKg === 0 ? "-45" : `+${fromKg}`);

/** A rate higher than the break before it is the start-kit trap: billing at the next break can be cheaper. */
function isTrap(lane: TariffLane, i: number) {
  return i > 0 && Number(lane.breaks[i].rate) >= Number(lane.breaks[i - 1].rate);
}

function LaneTable({ tariff }: { tariff: TariffDetail }) {
  const [q, setQ] = useState("");
  const breaks = tariff.lanes[0]?.breaks ?? [];
  const lanes = tariff.lanes.filter((l) => !q || `${l.origin}-${l.destination}`.toLowerCase().includes(q.toLowerCase()));
  return (
    <div>
      <div className="flex flex-wrap items-center gap-3 border-b border-line px-4 py-2.5">
        <SearchInput value={q} onChange={setQ} placeholder="Filter lanes, e.g. FRA-SIN" className="w-56" />
        <span className="flex items-center gap-1.5 text-2xs text-subtle">
          <span className="size-2 rounded-sm bg-attn/50" /> rate not lower than the break before
        </span>
      </div>
      <div className="max-h-[560px] overflow-auto">
        <table className="w-full text-[13px]">
          <thead className="sticky top-0 z-[1] border-b border-line bg-surface">
            <tr className="eyebrow">
              <th className="px-3 py-2 text-left font-medium">Lane</th>
              <th className="px-2 py-2 text-right font-medium">Days</th>
              <th className="px-2 py-2 text-right font-medium">Min</th>
              {breaks.map((b) => (
                <th key={b.fromKg} className="px-2 py-2 text-right font-medium">
                  {breakLabel(b.fromKg)}
                </th>
              ))}
              <th className="px-2 py-2 text-right font-medium">Fuel</th>
              <th className="px-3 py-2 text-right font-medium">Sec.</th>
            </tr>
          </thead>
          <tbody className="num">
            {lanes.map((l) => (
              <tr key={`${l.origin}-${l.destination}`} className="border-b border-line last:border-b-0 hover:bg-surface-2">
                <td className="px-3 py-1.5 font-medium">
                  {l.origin}-{l.destination}
                </td>
                <td className="px-2 py-1.5 text-right text-muted">{l.transitDays ?? "—"}</td>
                <td className="px-2 py-1.5 text-right text-muted">{l.minimum}</td>
                {l.breaks.map((b, i) => (
                  <td key={b.fromKg} className={cn("px-2 py-1.5 text-right", isTrap(l, i) && "bg-attn/15 text-attn")}>
                    {b.rate}
                  </td>
                ))}
                <td className="px-2 py-1.5 text-right text-muted">{l.fuelPerKg}</td>
                <td className="px-3 py-1.5 text-right text-muted">{l.securityPerKg}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {lanes.length === 0 && <p className="py-8 text-center text-xs text-subtle">No lane matches.</p>}
      </div>
    </div>
  );
}

function Detail({ id }: { id: number }) {
  const qc = useQueryClient();
  const toast = useToast();
  const { data, isPending, isError, error } = useGetTariff(id);
  const refresh = () => void qc.invalidateQueries({ predicate: byPath("/api/tariffs", "/api/tariffs/*") });
  const activate = useActivateTariff({
    mutation: { onSuccess: (t) => (toast.ok(`${t.airlineName} ${t.version} is active`), refresh()), onError: (e) => toast.error("Could not activate", e.message) },
  });
  const reject = useRejectTariff({
    mutation: { onSuccess: (t) => (toast.ok(`${t.airlineName} ${t.version} rejected`), refresh()), onError: (e) => toast.error("Could not reject", e.message) },
  });

  if (isPending) return <div className="p-4"><Skeleton className="h-80 w-full" /></div>;
  if (isError) return <ErrorState error={error} />;

  return (
    <div>
      <div className="flex flex-wrap items-start gap-4 border-b border-line px-4 py-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h2 className="text-base font-semibold tracking-tight">{data.airlineName}</h2>
            <span className="num text-sm text-muted">{data.version}</span>
            <TariffChip status={data.status} />
            <Badge tone="muted">{data.source}</Badge>
          </div>
          <p className="num mt-1 text-xs text-muted">
            valid {data.validFrom ?? "?"} → {data.validTo ?? "?"} · {data.currency} · {data.laneCount} lanes · ingested {dateTime(data.createdAt)}
          </p>
        </div>
        {data.status === "pending_review" && (
          <div className="flex gap-2">
            <Button variant="danger" icon={<X className="size-3.5" />} loading={reject.isPending} onClick={() => reject.mutate({ id })}>
              Reject
            </Button>
            <Button variant="primary" icon={<Check className="size-3.5" />} loading={activate.isPending} onClick={() => activate.mutate({ id })}>
              Activate
            </Button>
          </div>
        )}
      </div>
      {data.notes && (
        <div className={cn("flex items-start gap-2 border-b border-line px-4 py-2.5 text-[13px]", data.status === "pending_review" ? "bg-attn/[0.07] text-attn" : "text-muted")}>
          {data.status === "pending_review" && <TriangleAlert className="mt-0.5 size-4 shrink-0" />}
          <p>{data.notes}</p>
        </div>
      )}
      <LaneTable tariff={data} />
    </div>
  );
}

export function TariffsView() {
  const { data, isPending, isError, error } = useListTariffs();
  const [selected, setSelected] = useState<number | null>(null);

  const groups = useMemo(() => {
    const map = new Map<string, TariffSummary[]>();
    for (const t of data ?? []) map.set(t.airlineName, [...(map.get(t.airlineName) ?? []), t]);
    const rank = (t: TariffSummary) => (t.status === "pending_review" ? 0 : t.status === "active" ? 1 : 2);
    return [...map.entries()]
      .map(([airline, versions]) => ({ airline, versions: versions.sort((a, b) => rank(a) - rank(b) || b.version.localeCompare(a.version)) }))
      .sort((a, b) => rank(a.versions[0]) - rank(b.versions[0]) || a.airline.localeCompare(b.airline));
  }, [data]);

  const pending = (data ?? []).filter((t) => t.status === "pending_review").length;
  const current = selected ?? groups[0]?.versions[0]?.id ?? null;

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[300px_minmax(0,1fr)]">
      <Panel title="Airlines" icon={<Table2 />} count={groups.length} actions={pending > 0 && <Badge tone="attn" dot pulse>{pending} to review</Badge>} bodyClassName="lg:max-h-[calc(100dvh-12rem)] overflow-y-auto">
        {isPending ? (
          <SkeletonRows rows={8} />
        ) : isError ? (
          <ErrorState error={error} />
        ) : groups.length === 0 ? (
          <EmptyState icon={Table2} title="No tariffs loaded" />
        ) : (
          <ul>
            {groups.map((g) => (
              <li key={g.airline} className="border-b border-line px-2 py-2 last:border-b-0">
                <p className="px-2 pb-1 text-[13px] font-medium">{g.airline}</p>
                <ul className="flex flex-col gap-0.5">
                  {g.versions.map((v) => (
                    <li key={v.id}>
                      <button
                        onClick={() => setSelected(v.id)}
                        aria-current={current === v.id || undefined}
                        className={cn(
                          "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs transition-colors hover:bg-surface-2",
                          current === v.id && "bg-surface-3",
                        )}
                      >
                        <span className="num">{v.version}</span>
                        <span className="text-subtle">{v.source}</span>
                        <span className="num text-subtle">{v.laneCount} lanes</span>
                        <span className="ml-auto">
                          <TariffChip status={v.status} />
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <section className="panel min-w-0 overflow-hidden">
        {current != null ? <Detail key={current} id={current} /> : <EmptyState icon={Table2} title="Pick a tariff version" />}
      </section>
    </div>
  );
}
