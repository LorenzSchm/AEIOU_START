import type { BookingStatus, SessionStatus, SessionType, TariffStatus } from "@/api/generated/model";
import { Badge, type Tone } from "@/components/ui/badge";

const SESSION_STATUS: Record<SessionStatus, { label: string; tone: Tone; pulse?: boolean }> = {
  NEW: { label: "New", tone: "accent", pulse: true },
  PRICING: { label: "Pricing", tone: "cyan", pulse: true },
  WAITING_FOR_BIDS: { label: "Waiting for bids", tone: "violet", pulse: true },
  QUOTED: { label: "Quoted", tone: "accent" },
  ACCEPTED: { label: "Accepted", tone: "ok", pulse: true },
  BOOKED: { label: "Booked", tone: "ok" },
  CONFIRMED: { label: "Confirmed", tone: "ok" },
  ANSWERED: { label: "Answered", tone: "ok" },
  CLOSED: { label: "Closed", tone: "muted" },
  NEEDS_HUMAN: { label: "Needs human", tone: "attn", pulse: true },
  FAILED: { label: "Failed", tone: "danger" },
  EXPIRED: { label: "Expired", tone: "muted" },
};

export const SESSION_STATUSES = Object.keys(SESSION_STATUS) as SessionStatus[];

export function statusLabel(s: SessionStatus): string {
  return SESSION_STATUS[s].label;
}

export function StatusChip({ status, className }: { status: SessionStatus; className?: string }) {
  const meta = SESSION_STATUS[status] ?? { label: status, tone: "neutral" as Tone };
  return (
    <Badge tone={meta.tone} dot pulse={meta.pulse} className={className}>
      {meta.label}
    </Badge>
  );
}

const TYPE_LABEL: Record<SessionType, string> = { RFQ: "RFQ", TRACKING: "Tracking", TARIFF: "Tariff", OTHER: "Other" };
export function TypeTag({ type }: { type: SessionType }) {
  return <span className="eyebrow !text-[10px] !tracking-[0.06em]">{TYPE_LABEL[type]}</span>;
}

const BOOKING: Record<BookingStatus, Tone> = { requested: "cyan", booked: "accent", confirmed: "ok", failed: "danger" };
export function BookingChip({ status }: { status: BookingStatus }) {
  return (
    <Badge tone={BOOKING[status]} dot>
      {status}
    </Badge>
  );
}

const TARIFF: Record<TariffStatus, { tone: Tone; label: string }> = {
  active: { tone: "ok", label: "Active" },
  pending_review: { tone: "attn", label: "Pending review" },
  superseded: { tone: "muted", label: "Superseded" },
  rejected: { tone: "danger", label: "Rejected" },
};
export function TariffChip({ status }: { status: TariffStatus }) {
  const m = TARIFF[status];
  return (
    <Badge tone={m.tone} dot pulse={status === "pending_review"}>
      {m.label}
    </Badge>
  );
}

export function verdictTone(v: string | null | undefined): Tone {
  switch (v) {
    case "accepted":
      return "ok";
    case "rejected":
      return "danger";
    case "pending":
    case "open":
      return "accent";
    case "no answer":
    case "voided":
      return "muted";
    default:
      return "muted";
  }
}

export function VerdictChip({ verdict }: { verdict: string | null | undefined }) {
  if (!verdict) return <span className="text-subtle">—</span>;
  return <Badge tone={verdictTone(verdict)}>{verdict}</Badge>;
}

export function ModeTag({ mode }: { mode: string | null | undefined }) {
  if (!mode) return <span className="text-subtle">—</span>;
  return (
    <span className={mode === "fastest" ? "text-pink" : "text-cyan"}>
      {mode === "fastest" ? "fastest" : "cheapest"}
    </span>
  );
}
