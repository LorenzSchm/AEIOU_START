"use client";

import { Activity } from "lucide-react";
import Link from "next/link";
import { useListEventsInfinite } from "@/api/generated/events/events";
import { kindMeta } from "@/components/session/event-kinds";
import { TONE_TEXT } from "@/components/ui/badge";
import { LoadMore } from "@/components/ui/load-more";
import { Panel } from "@/components/ui/panel";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/states";
import { cn } from "@/lib/cn";
import { time } from "@/lib/format";
import { useLive } from "@/lib/live/live-provider";
import { cursorPaging, flatten } from "@/lib/pagination";

/** The global feed: every event the system writes, newest first, straight from SSE. */
export function EventTicker({ className }: { className?: string }) {
  const query = useListEventsInfinite({ limit: 60 }, { query: cursorPaging });
  const events = flatten(query.data);
  const { status } = useLive();

  return (
    <Panel
      title="Live feed"
      icon={<Activity />}
      className={className}
      bodyClassName="overflow-y-auto"
      actions={
        <span className={cn("flex items-center gap-1.5 text-2xs", status === "live" ? "text-ok" : "text-attn")}>
          <span className={cn("size-1.5 rounded-full", status === "live" ? "animate-pulse-dot bg-ok" : "bg-attn")} />
          {status === "live" ? "streaming" : "paused"}
        </span>
      }
    >
      {query.isPending ? (
        <SkeletonRows rows={10} />
      ) : query.isError ? (
        <ErrorState error={query.error} />
      ) : events.length === 0 ? (
        <EmptyState icon={Activity} title="No events yet" hint="Every mail, LLM call, portal call and decision appears here." />
      ) : (
        <ol className="relative">
          {events.map((e) => {
            const meta = kindMeta(e.kind);
            const Icon = meta.icon;
            const body = (
              <>
                <Icon className={cn("mt-0.5 size-3.5 shrink-0", TONE_TEXT[meta.tone])} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline gap-2">
                    <span className={cn("text-2xs font-medium", TONE_TEXT[meta.tone])}>{meta.label}</span>
                    {e.sessionId != null && <span className="num text-2xs text-subtle">#{e.sessionId}</span>}
                    <span className="num ml-auto text-2xs text-subtle">{time(e.createdAt)}</span>
                  </div>
                  <p className="mt-0.5 line-clamp-2 text-xs text-muted">{e.summary}</p>
                </div>
              </>
            );
            return (
              <li key={e.id} className="animate-slide-in border-b border-line last:border-b-0">
                {e.sessionId != null ? (
                  <Link href={`/sessions/${e.sessionId}`} className="flex gap-2.5 px-3.5 py-2 transition-colors hover:bg-surface-2">
                    {body}
                  </Link>
                ) : (
                  <div className="flex gap-2.5 px-3.5 py-2">{body}</div>
                )}
              </li>
            );
          })}
          <LoadMore hasMore={Boolean(query.hasNextPage)} loading={query.isFetchingNextPage} onLoad={() => void query.fetchNextPage()} />
        </ol>
      )}
    </Panel>
  );
}
