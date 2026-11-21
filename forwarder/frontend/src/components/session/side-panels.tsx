"use client";

import { CircleCheck, Gavel, PackageCheck, Plane, Scale, ScrollText } from "lucide-react";
import type { BidRequest, Booking, PriceCandidate, Rfq } from "@/api/generated/model";
import { Badge, type Tone } from "@/components/ui/badge";
import { Facts, Panel } from "@/components/ui/panel";
import { EmptyState } from "@/components/ui/states";
import { cn } from "@/lib/cn";
import { ago, dateTime, kg, money, shortDate } from "@/lib/format";
import { useNow } from "@/lib/use-now";
import { BookingChip } from "./status";

export function RfqFacts({ rfq }: { rfq: Rfq | null }) {
  if (!rfq) {
    return (
      <Panel title="RFQ" icon={<ScrollText />}>
        <p className="px-4 py-4 text-xs text-subtle">—</p>
      </Panel>
    );
  }
  return (
    <Panel title="RFQ" icon={<ScrollText />} actions={rfq.language && <Badge tone="muted">{rfq.language.toUpperCase()}</Badge>}>
      <div className="px-4 py-3">
        <div className="mb-3 flex items-center gap-3" title={[rfq.customerName, rfq.commodity, rfq.pieces ? `${rfq.pieces} pcs` : null].filter(Boolean).join(" · ")}>
          <div className="text-center">
            <div className="num text-lg font-semibold">{rfq.originAirport ?? "—"}</div>
            <div className="text-2xs text-subtle">{rfq.doorPickup ? `pickup ${rfq.originZip ?? ""}` : "airport"}</div>
          </div>
          <div className="relative h-px flex-1 bg-line-strong">
            <Plane className="absolute -top-2 left-1/2 size-4 -translate-x-1/2 rotate-45 text-accent" />
          </div>
          <div className="text-center">
            <div className="num text-lg font-semibold">{rfq.destination ?? "—"}</div>
            <div className="text-2xs text-subtle">destination</div>
          </div>
        </div>
        <Facts
          items={[
            { label: "Weight", value: kg(rfq.weightKg), mono: true },
            { label: "Volume", value: rfq.volumeCbm ? `${rfq.volumeCbm} cbm` : "—", mono: true },
            { label: "Chargeable", value: <span className="font-semibold">{kg(rfq.chargeableKg)}</span>, mono: true },
            { label: "Ready", value: rfq.readyDate, mono: true },
          ]}
        />
      </div>
    </Panel>
  );
}

const SOURCE_TONE: Record<PriceCandidate["source"], Tone> = {
  sheet: "cyan",
  portal: "accent",
  bid: "pink",
  ratedesk: "violet",
  truck: "muted",
  combined: "ok",
};

export function CandidatesTable({ candidates, currency }: { candidates: PriceCandidate[]; currency?: string | null }) {
  const sorted = [...candidates].sort((a, b) => Number(b.chosen) - Number(a.chosen) || Number(a.totalEur) - Number(b.totalEur));
  const best = Math.min(...candidates.filter((c) => c.source !== "truck").map((c) => Number(c.totalEur)));
  return (
    <Panel title="Prices" icon={<Scale />} count={candidates.length}>
      {candidates.length === 0 ? (
        <EmptyState icon={Scale} title="No prices yet" className="py-8" />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="eyebrow border-b border-line text-left">
                <th className="px-3 py-2 font-medium">Source</th>
                <th className="px-2 py-2 font-medium">Airline</th>
                <th className="px-2 py-2 text-right font-medium">EUR</th>
                <th className="px-3 py-2 text-right font-medium">Days</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((c) => (
                <tr
                  key={c.id}
                  className={cn("border-b border-line align-top last:border-b-0", c.chosen ? "bg-ok/[0.07]" : "text-muted")}
                  title={c.rejectReason ?? undefined}
                >
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-1.5">
                      {c.chosen && <CircleCheck className="size-3.5 text-ok" />}
                      <Badge tone={SOURCE_TONE[c.source]}>{c.source}</Badge>
                    </div>
                  </td>
                  <td className="px-2 py-2">
                    <div className={cn("max-w-[140px] truncate", c.chosen && "font-medium text-fg")}>{c.airlineName ?? "—"}</div>
                    {c.currency !== "EUR" && <div className="num mt-0.5 text-2xs text-subtle">{money(c.total, c.currency)}</div>}
                  </td>
                  <td className={cn("num px-2 py-2 text-right", c.chosen && "font-semibold text-ok")}>
                    {money(c.totalEur, currency ?? "EUR")}
                    {!c.chosen && Number.isFinite(best) && c.source !== "truck" && Number(c.totalEur) > best && (
                      <div className="text-2xs text-subtle">+{(((Number(c.totalEur) - best) / best) * 100).toFixed(1)}%</div>
                    )}
                  </td>
                  <td className="num px-3 py-2 text-right">{c.transitDays ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

export function BidsPanel({ bids }: { bids: BidRequest[] }) {
  const now = useNow();
  if (bids.length === 0) return null;
  const answered = bids.filter((b) => b.answeredAt).length;
  return (
    <Panel title="Bids" icon={<Gavel />} actions={<span className="num text-2xs text-subtle">{answered}/{bids.length}</span>}>
      <ul className="divide-y divide-line">
        {bids.map((b) => {
          const state = b.declined ? "declined" : b.answeredAt ? "answered" : "pending";
          return (
            <li key={b.id} className="flex h-10 items-center gap-3 px-4" title={`${b.kind} · asked ${ago(b.sentAt, now)} ago`}>
              <span
                className={cn(
                  "size-2 shrink-0 rounded-full",
                  state === "answered" ? "bg-ok" : state === "declined" ? "bg-danger" : "animate-pulse-dot bg-attn",
                )}
              />
              <p className="min-w-0 flex-1 truncate text-[13px]">{b.carrierName}</p>
              <div className="text-right">
                {state === "answered" ? (
                  <p className="num text-xs">
                    {money(b.price, b.currency)}
                    {b.transitDays != null && <span className="ml-2 text-subtle">{b.transitDays}d</span>}
                  </p>
                ) : (
                  <Badge tone={state === "declined" ? "danger" : "attn"}>{state}</Badge>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}

export function BookingCard({ booking }: { booking: Booking | null }) {
  if (!booking) return null;
  return (
    <Panel title="Booking" icon={<PackageCheck />} actions={<BookingChip status={booking.status} />}>
      <div className="px-4 py-3">
        <p className="num text-lg font-semibold tracking-tight">{booking.bookingReference ?? "pending"}</p>
        <p className="mb-3 text-xs text-muted" title={`Team ref ${booking.teamReference} · rate ${booking.rateId ?? "—"} · booked ${dateTime(booking.bookedAt)}`}>
          {booking.airlineName}
        </p>
        <Facts
          items={[
            { label: "Lane", value: `${booking.origin} → ${booking.destination}`, mono: true },
            { label: "Price", value: money(booking.price, booking.currency), mono: true },
            { label: "Departure", value: shortDate(booking.departureAt), mono: true },
            { label: "ETA", value: shortDate(booking.etaAt), mono: true },
          ]}
        />
      </div>
    </Panel>
  );
}
