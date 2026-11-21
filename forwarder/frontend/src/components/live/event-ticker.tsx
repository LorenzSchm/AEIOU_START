"use client";

import { Activity, PanelRightClose, PanelRightOpen } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { useListEventsInfinite } from "@/api/generated/events/events";
import { kindMeta } from "@/components/session/event-kinds";
import { TONE_TEXT } from "@/components/ui/badge";
import { LoadMore } from "@/components/ui/load-more";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/states";
import { cn } from "@/lib/cn";
import { time } from "@/lib/format";
import { useLive } from "@/lib/live/live-provider";
import { cursorPaging, flatten } from "@/lib/pagination";

const STORAGE_KEY = "forwarder.feed-open";

/** The global feed as a slim, collapsible rail: one line per event, newest first, straight from SSE. */
export function EventTicker() {
  const query = useListEventsInfinite({ limit: 60 }, { query: cursorPaging });
  const events = flatten(query.data);
  const { status } = useLive();
  const [open, setOpen] = useState(true);

  useEffect(() => {
    try {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- restore a per-viewer preference after hydration
      if (localStorage.getItem(STORAGE_KEY) === "0") setOpen(false);
    } catch {
      // storage unavailable: keep the default
    }
  }, []);
  const toggle = () =>
    setOpen((v) => {
      try {
        localStorage.setItem(STORAGE_KEY, v ? "0" : "1");
      } catch {
        // ignore
      }
      return !v;
    });

  const live = status === "live";

  if (!open) {
    return (
      <aside className="panel sticky top-0 hidden h-[calc(100dvh-9.5rem)] w-11 shrink-0 flex-col items-center gap-3 py-3 lg:flex">
        <button onClick={toggle} className="rounded-md p-1 text-subtle hover:bg-surface-3 hover:text-fg" aria-label="Show live feed" title="Live feed">
          <PanelRightOpen className="size-4" />
        </button>
        <span className={cn("size-1.5 rounded-full", live ? "animate-pulse-dot bg-ok" : "bg-attn")} />
        <span className="num text-2xs text-subtle [writing-mode:vertical-rl]">{events.length} events</span>
      </aside>
    );
  }

  return (
    <aside className="panel sticky top-0 hidden h-[calc(100dvh-9.5rem)] w-[300px] shrink-0 flex-col overflow-hidden lg:flex">
      <header className="flex h-11 shrink-0 items-center gap-2 border-b border-line px-4">
        <Activity className="size-3.5 text-subtle" />
        <h2 className="text-[13px] font-semibold">Feed</h2>
        <span className={cn("size-1.5 rounded-full", live ? "animate-pulse-dot bg-ok" : "bg-attn")} title={live ? "Streaming" : "Paused"} />
        <button onClick={toggle} className="ml-auto rounded-md p-1 text-subtle hover:bg-surface-3 hover:text-fg" aria-label="Hide live feed">
          <PanelRightClose className="size-4" />
        </button>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {query.isPending ? (
          <SkeletonRows rows={10} />
        ) : query.isError ? (
          <ErrorState error={query.error} />
        ) : events.length === 0 ? (
          <EmptyState icon={Activity} title="No events" />
        ) : (
          <ol>
            {events.map((e) => {
              const meta = kindMeta(e.kind);
              const Icon = meta.icon;
              const row = (
                <>
                  <Icon className={cn("size-3.5 shrink-0", TONE_TEXT[meta.tone])} />
                  <span className="min-w-0 flex-1 truncate text-xs text-fg/85">{e.summary}</span>
                  <span className="num shrink-0 text-2xs text-subtle">{time(e.createdAt)}</span>
                </>
              );
              const cls = "flex h-8 items-center gap-2.5 px-4";
              return (
                <li key={e.id} className="animate-slide-in" title={`${meta.label}: ${e.summary}`}>
                  {e.sessionId != null ? (
                    <Link href={`/sessions/${e.sessionId}`} className={cn(cls, "transition-colors hover:bg-surface-2")}>
                      {row}
                    </Link>
                  ) : (
                    <div className={cls}>{row}</div>
                  )}
                </li>
              );
            })}
            <LoadMore hasMore={Boolean(query.hasNextPage)} loading={query.isFetchingNextPage} onLoad={() => void query.fetchNextPage()} />
          </ol>
        )}
      </div>
    </aside>
  );
}
