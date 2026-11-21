"use client";

import { ArrowUpRight, Hand } from "lucide-react";
import Link from "next/link";
import { useListSessions } from "@/api/generated/sessions/sessions";
import { StatusChip } from "@/components/session/status";
import { addressName, ago } from "@/lib/format";
import { useNow } from "@/lib/use-now";
import { Countdown } from "./countdown";

/** Pinned on top of the live board: everything the agents handed to a person. */
export function NeedsHumanQueue() {
  const { data } = useListSessions({ needsHuman: true, limit: 20 });
  const now = useNow();
  const items = data?.items ?? [];
  if (items.length === 0) return null;

  return (
    <section className="overflow-hidden rounded-[10px] border border-attn/35 bg-attn/[0.06] shadow-panel" aria-label="Needs human">
      <header className="flex items-center gap-2 border-b border-attn/20 px-4 py-2.5">
        <span className="grid size-5 place-items-center rounded-md bg-attn text-[#1b1200]">
          <Hand className="size-3" />
        </span>
        <h2 className="text-[13px] font-semibold text-attn">Needs a human</h2>
        <span className="num text-xs text-attn/80">{items.length}</span>
        <span className="ml-auto hidden text-2xs text-muted sm:inline">The agents parked these and are waiting for a decision.</span>
      </header>
      <ul className="divide-y divide-attn/15">
        {items.map((s) => (
          <li key={s.id}>
            <Link
              href={`/sessions/${s.id}`}
              className="group flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-2.5 transition-colors hover:bg-attn/[0.06]"
            >
              <span className="num w-[72px] text-xs font-semibold text-attn">{s.publicRef}</span>
              <StatusChip status={s.status} />
              <span className="min-w-0 flex-1 truncate text-[13px]">
                <span className="text-muted">{addressName(s.customerAddr)} · </span>
                {s.needsHumanReason ?? s.lastEventSummary}
              </span>
              {s.deadlineAt && !s.quotedAt && <Countdown session={s} />}
              <span className="num text-2xs text-subtle">{ago(s.lastEventAt, now)}</span>
              <ArrowUpRight className="size-3.5 text-subtle transition-colors group-hover:text-attn" />
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
