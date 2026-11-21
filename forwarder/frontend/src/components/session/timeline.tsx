"use client";

import { parseISO } from "date-fns";
import { ChevronRight, ListTree } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useGetEventPayload } from "@/api/generated/events/events";
import { useListLlmCallsInfinite } from "@/api/generated/llm/llm";
import type { EventItem, LlmCall, MailDetail } from "@/api/generated/model";
import { useListSessionEventsInfinite, useListSessionMails } from "@/api/generated/sessions/sessions";
import { MailCard } from "@/components/mail/mail-card";
import { TONE_TEXT } from "@/components/ui/badge";
import { Select } from "@/components/ui/fields";
import { JsonView } from "@/components/ui/json-view";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui/states";
import { cn } from "@/lib/cn";
import { clock, fullDateTime, ms, usd } from "@/lib/format";
import { cursorPaging, flatten } from "@/lib/pagination";
import { kindMeta } from "./event-kinds";
import { useSessionActions } from "./use-session-action";

const MAIL_TYPES = ["NEW_RFQ", "ACCEPTANCE", "FOLLOW_UP", "CARRIER_BID", "TARIFF", "TRACKING_QUESTION", "BOOKING_CONFIRMATION", "OTHER"];

function Payload({ eventId }: { eventId: number }) {
  const { data, isPending, isError, error } = useGetEventPayload(eventId);
  if (isPending) return <Skeleton className="h-24 w-full" />;
  if (isError) return <ErrorState error={error} className="py-4" />;
  return <JsonView value={data.payload} />;
}

function ConfidenceBar({ value }: { value: number }) {
  const low = value < 0.75;
  return (
    <div className="mb-2 flex items-center gap-2" title="Router confidence (threshold 0.75)">
      <div className="relative h-1.5 w-40 overflow-hidden rounded-full bg-surface-3">
        <div className={cn("h-full rounded-full", low ? "bg-attn" : "bg-violet")} style={{ width: `${value * 100}%` }} />
        <div className="absolute top-0 h-full w-px bg-fg/50" style={{ left: "75%" }} />
      </div>
      <span className={cn("num text-2xs", low ? "text-attn" : "text-muted")}>{value.toFixed(2)}</span>
    </div>
  );
}

function LlmChips({ call }: { call: LlmCall }) {
  const chip = "num rounded border border-line bg-surface-2 px-1.5 py-px text-2xs text-muted";
  return (
    <div className="mb-2 flex flex-wrap gap-1.5">
      <span className={cn(chip, "text-violet")}>{call.model}</span>
      <span className={chip}>{call.purpose}</span>
      <span className={chip}>
        {call.promptTokens ?? "?"} → {call.completionTokens ?? "?"} tok
      </span>
      <span className={chip}>{usd(call.costUsd)}</span>
      <span className={chip}>{ms(call.latencyMs)}</span>
      {!call.success && <span className={cn(chip, "text-danger")}>failed</span>}
    </div>
  );
}

/** Stage-2 decisions carry their confidence in the summary ("… confidence 0.93"). */
function confidenceOf(e: EventItem): number | null {
  if (e.kind !== "route.decision") return null;
  const m = e.summary.match(/confidence\s+([01](?:\.\d+)?)/i);
  return m ? Number(m[1]) : null;
}

function TimelineEntry({
  event,
  startedAt,
  mail,
  llm,
  rerouteSlot,
}: {
  event: EventItem;
  startedAt: number;
  mail?: MailDetail;
  llm?: LlmCall;
  rerouteSlot?: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const meta = kindMeta(event.kind);
  const Icon = meta.icon;
  const offset = (parseISO(event.createdAt).getTime() - startedAt) / 1000;
  const isMail = (event.kind === "mail.in" || event.kind === "mail.out") && mail;
  const confidence = confidenceOf(event);
  const expandable = Boolean(isMail || llm || confidence != null || event.hasPayload);
  // A mail event already shows the whole mail; its JSON payload would only repeat it.
  const summary = isMail ? `${mail.direction === "in" ? mail.fromAddr : mail.toAddr} · ${mail.subject}` : event.summary;

  return (
    <li className="border-b border-line last:border-b-0">
      <button
        type="button"
        onClick={() => expandable && setOpen((v) => !v)}
        aria-expanded={expandable ? open : undefined}
        title={`${meta.label} · ${fullDateTime(event.createdAt)}`}
        className={cn(
          "flex h-9 w-full items-center gap-3 px-4 text-left transition-colors",
          expandable ? "hover:bg-surface-2" : "cursor-default",
        )}
      >
        <Icon className={cn("size-3.5 shrink-0", TONE_TEXT[meta.tone])} />
        <span className="min-w-0 flex-1 truncate text-[13px] text-fg/90">{summary}</span>
        {event.actor === "human" && <span className="rounded bg-attn/15 px-1 text-[10px] font-semibold text-attn uppercase">human</span>}
        <span className="num shrink-0 text-2xs text-subtle">+{clock(offset)}</span>
        <ChevronRight className={cn("size-3.5 shrink-0 text-subtle transition-transform", open && "rotate-90", !expandable && "opacity-0")} />
      </button>

      {open && (
        <div className="animate-fade-in px-4 pb-3 pl-10">
          {confidence != null && <ConfidenceBar value={confidence} />}
          {event.kind === "llm.call" && llm && <LlmChips call={llm} />}
          {isMail && (
            <div className="mt-1">
              <MailCard mail={mail} compact actions={rerouteSlot} />
            </div>
          )}
          {event.hasPayload && !isMail && (
            <div className="mt-2">
              <Payload eventId={event.id} />
            </div>
          )}
        </div>
      )}
    </li>
  );
}

function RerouteSelect({ sessionId, current }: { sessionId: number; current: string | null }) {
  const { run, pending } = useSessionActions(sessionId);
  return (
    <label className="flex items-center gap-2 text-2xs text-subtle">
      Re-route
      <Select
        value=""
        disabled={pending === "reroute"}
        onChange={(e) => e.target.value && run({ action: "reroute", type: e.target.value })}
        className="h-6 w-40 text-xs"
        aria-label="Re-route this mail"
      >
        <option value="">{current ? current.replaceAll("_", " ").toLowerCase() : "choose type"}…</option>
        {MAIL_TYPES.filter((t) => t !== current).map((t) => (
          <option key={t} value={t}>
            {t.replaceAll("_", " ").toLowerCase()}
          </option>
        ))}
      </Select>
    </label>
  );
}

/** Pair each llm.call event with the LLM call row closest in time. */
function matchLlm(events: EventItem[], calls: LlmCall[]): Map<number, LlmCall> {
  const out = new Map<number, LlmCall>();
  const used = new Set<number>();
  for (const e of events) {
    if (e.kind !== "llm.call") continue;
    const t = parseISO(e.createdAt).getTime();
    let best: LlmCall | undefined;
    let bestDiff = 10_000;
    for (const c of calls) {
      if (used.has(c.id)) continue;
      const d = Math.abs(parseISO(c.createdAt).getTime() - t);
      if (d < bestDiff) {
        best = c;
        bestDiff = d;
      }
    }
    if (best) {
      used.add(best.id);
      out.set(e.id, best);
    }
  }
  return out;
}

export function Timeline({ sessionId, startedAt }: { sessionId: number; startedAt: string }) {
  const events = useListSessionEventsInfinite(sessionId, { limit: 200 }, { query: cursorPaging });
  const mails = useListSessionMails(sessionId);
  const llm = useListLlmCallsInfinite({ sessionId, limit: 200 }, { query: cursorPaging });
  const [filter, setFilter] = useState<"all" | "mail" | "decisions">("all");

  // A timeline is only useful complete: pull every page.
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = events;
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  const items = flatten(events.data);
  const mailById = useMemo(() => new Map((mails.data ?? []).map((m) => [m.id, m])), [mails.data]);
  const llmByEvent = useMemo(() => matchLlm(items, flatten(llm.data)), [items, llm.data]);
  const latestInbound = useMemo(() => [...(mails.data ?? [])].reverse().find((m) => m.direction === "in"), [mails.data]);

  const visible = items.filter((e) =>
    filter === "all" ? true : filter === "mail" ? e.kind.startsWith("mail.") : !e.kind.startsWith("mail.") && e.kind !== "llm.call",
  );
  const start = parseISO(startedAt).getTime();

  return (
    <section className="panel overflow-hidden">
      <header className="flex h-11 items-center gap-2 border-b border-line px-4">
        <ListTree className="size-3.5 text-subtle" />
        <h2 className="text-[13px] font-semibold">Timeline</h2>
        <span className="num text-xs text-subtle">{items.length}</span>
        <div className="ml-auto flex items-center gap-1 text-xs">
          {(["all", "mail", "decisions"] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={cn("rounded-md px-2 py-0.5 capitalize", filter === f ? "bg-surface-3 text-fg" : "text-subtle hover:text-fg")}
            >
              {f}
            </button>
          ))}
        </div>
      </header>
      <div>
        {events.isPending ? (
          <div className="flex flex-col gap-3 p-4">
            {Array.from({ length: 8 }, (_, i) => (
              <Skeleton key={i} className="h-4 w-full" />
            ))}
          </div>
        ) : events.isError ? (
          <ErrorState error={events.error} />
        ) : visible.length === 0 ? (
          <EmptyState icon={ListTree} title="Nothing here yet" />
        ) : (
          <ol>
            {visible.map((e) => {
              const mail = e.mailId != null ? mailById.get(e.mailId) : undefined;
              const isLatestInbound = mail && latestInbound && mail.id === latestInbound.id && e.kind === "mail.in";
              return (
                <TimelineEntry
                  key={e.id}
                  event={e}
                  startedAt={start}
                  mail={mail}
                  llm={llmByEvent.get(e.id)}
                  rerouteSlot={isLatestInbound ? <RerouteSelect sessionId={sessionId} current={mail.routeType} /> : undefined}
                />
              );
            })}
          </ol>
        )}
      </div>
    </section>
  );
}
