"use client";

import { ChartColumn, PackageCheck, Trophy } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { useListBoardResults } from "@/api/generated/board/board";
import { useListBookingsInfinite } from "@/api/generated/bookings/bookings";
import type { BookingStatus, ListBookingsParams } from "@/api/generated/model";
import { useListSessionsInfinite } from "@/api/generated/sessions/sessions";
import { useGetStats } from "@/api/generated/stats/stats";
import { BookingChip, VerdictChip } from "@/components/session/status";
import { Segmented } from "@/components/ui/fields";
import { LoadMore } from "@/components/ui/load-more";
import { Panel } from "@/components/ui/panel";
import { EmptyState, ErrorState, Skeleton, SkeletonRows } from "@/components/ui/states";
import { dateTime, money, shortDate } from "@/lib/format";
import { cursorPaging, flatten } from "@/lib/pagination";
import { CostPerHour, QuotesPerHour, ReasonCodes, ResponseDistribution } from "./charts";

const TH = "eyebrow px-3 py-2 text-left font-medium whitespace-nowrap";
const TD = "px-3 py-2.5 whitespace-nowrap";

function Bookings() {
  const [status, setStatus] = useState<"all" | BookingStatus>("all");
  const params: ListBookingsParams = { limit: 50, ...(status !== "all" ? { status } : {}) };
  const q = useListBookingsInfinite(params, { query: cursorPaging });
  const rows = flatten(q.data);
  return (
    <Panel
      title="Bookings"
      icon={<PackageCheck />}
      count={rows.length}
      actions={
        <Segmented<"all" | BookingStatus>
          size="xs"
          value={status}
          onChange={setStatus}
          options={[
            { value: "all", label: "All" },
            { value: "booked", label: "Booked" },
            { value: "confirmed", label: "Confirmed" },
            { value: "failed", label: "Failed" },
          ]}
        />
      }
    >
      {q.isPending ? (
        <SkeletonRows rows={5} />
      ) : q.isError ? (
        <ErrorState error={q.error} />
      ) : rows.length === 0 ? (
        <EmptyState icon={PackageCheck} title="No bookings yet" hint="Accepted quotes are booked by rate ID and land here." />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead className="border-b border-line">
              <tr>
                <th className={TH}>Booking</th>
                <th className={TH}>Status</th>
                <th className={TH}>Airline</th>
                <th className={TH}>Lane</th>
                <th className={`${TH} text-right`}>Price</th>
                <th className={TH}>Departure</th>
                <th className={TH}>ETA</th>
                <th className={TH}>Session</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((b) => (
                <tr key={b.id} className="border-b border-line last:border-b-0 hover:bg-surface-2">
                  <td className={`${TD} num font-medium`}>{b.bookingReference ?? "—"}</td>
                  <td className={TD}>
                    <BookingChip status={b.status} />
                  </td>
                  <td className={TD}>{b.airlineName ?? "—"}</td>
                  <td className={`${TD} num`}>
                    {b.origin} → {b.destination}
                  </td>
                  <td className={`${TD} num text-right`}>{money(b.price, b.currency)}</td>
                  <td className={`${TD} num text-muted`}>{shortDate(b.departureAt)}</td>
                  <td className={`${TD} num text-muted`}>{shortDate(b.etaAt)}</td>
                  <td className={TD}>
                    <Link href={`/sessions/${b.sessionId}`} className="num text-xs font-semibold text-accent hover:underline">
                      {b.publicRef}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <LoadMore hasMore={Boolean(q.hasNextPage)} loading={q.isFetchingNextPage} onLoad={() => void q.fetchNextPage()} />
        </div>
      )}
    </Panel>
  );
}

function Verdicts() {
  const { data, isPending, isError, error } = useListBoardResults();
  const rows = data ?? [];
  const won = rows.filter((r) => r.verdict === "accepted").length;
  const lost = rows.filter((r) => r.verdict === "rejected").length;
  return (
    <Panel
      title="Won / lost quotes"
      icon={<Trophy />}
      actions={
        <span className="num text-2xs">
          <span className="text-ok">{won} won</span> <span className="text-subtle">·</span> <span className="text-danger">{lost} lost</span>
        </span>
      }
      bodyClassName="max-h-[420px] overflow-y-auto"
    >
      {isPending ? (
        <SkeletonRows rows={5} />
      ) : isError ? (
        <ErrorState error={error} />
      ) : rows.length === 0 ? (
        <EmptyState icon={Trophy} title="No board verdicts yet" hint="The board watcher polls /v1/board/me every minute." />
      ) : (
        <table className="w-full text-[13px]">
          <thead className="sticky top-0 border-b border-line bg-surface">
            <tr>
              <th className={TH}>Round</th>
              <th className={TH}>Session</th>
              <th className={TH}>Verdict</th>
              <th className={TH}>Reason</th>
              <th className={`${TH} text-right`}>Points</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const pts = r.points ? Object.values(r.points).reduce((a, b) => a + b, 0) : null;
              return (
                <tr key={r.requestKey} className="border-b border-line last:border-b-0" title={r.points ? Object.entries(r.points).map(([k, v]) => `${k}: ${v}`).join(" · ") : undefined}>
                  <td className={`${TD} num text-muted`}>{r.round ?? "—"}</td>
                  <td className={TD}>
                    {r.sessionId != null ? (
                      <Link href={`/sessions/${r.sessionId}`} className="num text-xs font-semibold text-accent hover:underline">
                        {r.publicRef}
                      </Link>
                    ) : (
                      <span className="num text-xs text-subtle">{r.requestKey}</span>
                    )}
                  </td>
                  <td className={TD}>
                    <VerdictChip verdict={r.verdict} />
                  </td>
                  <td className={`${TD} num text-xs text-muted`}>{r.reasonCode ?? "—"}</td>
                  <td className={`${TD} num text-right`}>{pts != null ? pts.toFixed(1) : "—"}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

export function CrmView() {
  const stats = useGetStats({ hours: 24 });
  const sessions = useListSessionsInfinite({ type: "RFQ", limit: 200 }, { query: cursorPaging });
  const board = useListBoardResults();

  const chart = (node: React.ReactNode) => (stats.isPending ? <Skeleton className="h-[220px] w-full" /> : stats.isError ? <ErrorState error={stats.error} /> : node);

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-4">
        <Panel title="Quotes vs accepted" icon={<ChartColumn />} bodyClassName="p-4" actions={<span className="text-2xs text-subtle">per hour · last 12 h</span>}>
          {chart(stats.data && <QuotesPerHour hourly={stats.data.hourly} />)}
        </Panel>
        <Panel title="LLM cost" icon={<ChartColumn />} bodyClassName="p-4" actions={<span className="text-2xs text-subtle">updated {dateTime(stats.data?.hourly.at(-1)?.hour)}</span>}>
          {chart(stats.data && <CostPerHour hourly={stats.data.hourly} />)}
        </Panel>
        <Panel title="Response time" icon={<ChartColumn />} bodyClassName="p-4" actions={<span className="text-2xs text-subtle">request → quote</span>}>
          {sessions.isPending ? <Skeleton className="h-[220px] w-full" /> : <ResponseDistribution sessions={flatten(sessions.data)} />}
        </Panel>
        <Panel title="Rejection reasons" icon={<ChartColumn />} bodyClassName="p-4" actions={<span className="text-2xs text-subtle">early warning for setup changes</span>}>
          {board.isPending ? <Skeleton className="h-[220px] w-full" /> : <ReasonCodes board={board.data ?? []} />}
        </Panel>
      </div>
      <div className="grid grid-cols-1 gap-4 2xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <Bookings />
        <Verdicts />
      </div>
    </div>
  );
}
