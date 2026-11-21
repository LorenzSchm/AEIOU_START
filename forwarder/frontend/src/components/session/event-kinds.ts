import {
  Activity,
  BadgeCheck,
  Calculator,
  CircleX,
  Gavel,
  HandCoins,
  Hourglass,
  Inbox,
  PackageCheck,
  PlaneTakeoff,
  Scale,
  Send,
  Server,
  ServerCrash,
  Siren,
  Sparkles,
  Split,
  Table2,
  TriangleAlert,
  Trophy,
  UserRound,
  type LucideIcon,
} from "lucide-react";
import type { Tone } from "@/components/ui/badge";

export interface KindMeta {
  icon: LucideIcon;
  tone: Tone;
  label: string;
}

const EXACT: Record<string, KindMeta> = {
  "mail.in": { icon: Inbox, tone: "accent", label: "Mail in" },
  "mail.out": { icon: Send, tone: "ok", label: "Mail out" },
  "route.decision": { icon: Split, tone: "violet", label: "Routed" },
  "llm.call": { icon: Sparkles, tone: "violet", label: "LLM call" },
  "portal.rate": { icon: Server, tone: "cyan", label: "Portal rate" },
  "portal.booking": { icon: Server, tone: "cyan", label: "Portal booking" },
  "portal.error": { icon: ServerCrash, tone: "danger", label: "Portal error" },
  "bid.requested": { icon: Gavel, tone: "pink", label: "Bid requested" },
  "bid.received": { icon: HandCoins, tone: "pink", label: "Bid received" },
  "bid.timeout": { icon: Hourglass, tone: "muted", label: "Bid timeout" },
  "pricing.candidate": { icon: Calculator, tone: "cyan", label: "Candidate" },
  "pricing.decision": { icon: Scale, tone: "accent", label: "Pricing decision" },
  "pricing.mismatch": { icon: TriangleAlert, tone: "attn", label: "Price mismatch" },
  "quote.sent": { icon: BadgeCheck, tone: "ok", label: "Quote sent" },
  "booking.created": { icon: PackageCheck, tone: "ok", label: "Booked" },
  "booking.confirmed": { icon: PlaneTakeoff, tone: "ok", label: "Booking confirmed" },
  "tariff.ingested": { icon: Table2, tone: "cyan", label: "Tariff ingested" },
  "tariff.rejected": { icon: CircleX, tone: "danger", label: "Tariff rejected" },
  "board.verdict": { icon: Trophy, tone: "attn", label: "Board verdict" },
  "system.alert": { icon: Siren, tone: "danger", label: "Alert" },
};

export function kindMeta(kind: string): KindMeta {
  const exact = EXACT[kind];
  if (exact) return exact;
  if (kind.startsWith("human.")) return { icon: UserRound, tone: "attn", label: kind.slice(6).replaceAll("_", " ") };
  const prefix = Object.entries(EXACT).find(([k]) => k.split(".")[0] === kind.split(".")[0]);
  if (prefix) return { ...prefix[1], label: kind };
  return { icon: Activity, tone: "muted", label: kind };
}
