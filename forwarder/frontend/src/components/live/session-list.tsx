"use client";

import { Hand, Inbox } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import type { ListSessionsParams, SessionStatus, SessionType } from "@/api/generated/model";
import { useListSessionsInfinite } from "@/api/generated/sessions/sessions";
import { SESSION_STATUSES, statusLabel } from "@/components/session/status";
import { SearchInput, Segmented, Select } from "@/components/ui/fields";
import { LoadMore } from "@/components/ui/load-more";
import { Panel } from "@/components/ui/panel";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/states";
import { cn } from "@/lib/cn";
import { cursorPaging, flatten } from "@/lib/pagination";
import { useDebounced } from "@/lib/use-debounce";
import { useKeys } from "@/lib/use-hotkeys";
import { SessionHeaderRow, SessionRow } from "./session-row";

type TypeFilter = "all" | SessionType;

export function SessionList() {
  const router = useRouter();
  const [status, setStatus] = useState<SessionStatus | "">("");
  const [type, setType] = useState<TypeFilter>("all");
  const [onlyHuman, setOnlyHuman] = useState(false);
  const [search, setSearch] = useState("");
  const q = useDebounced(search.trim(), 250);

  const params = useMemo<ListSessionsParams>(
    () => ({
      limit: 40,
      ...(status ? { status } : {}),
      ...(type !== "all" ? { type } : {}),
      ...(onlyHuman ? { needsHuman: true } : {}),
      ...(q ? { q } : {}),
    }),
    [status, type, onlyHuman, q],
  );

  const query = useListSessionsInfinite(params, { query: cursorPaging });
  const sessions = flatten(query.data);

  // j/k selection, Enter opens.
  const [selected, setSelected] = useState(-1);
  const rows = useRef<(HTMLAnchorElement | null)[]>([]);
  const [lastParams, setLastParams] = useState(params);
  if (lastParams !== params) {
    setLastParams(params);
    setSelected(-1);
  }
  useEffect(() => {
    if (selected >= 0) rows.current[selected]?.scrollIntoView({ block: "nearest" });
  }, [selected]);

  useKeys({
    j: () => setSelected((i) => Math.min(sessions.length - 1, i + 1)),
    k: () => setSelected((i) => Math.max(0, i - 1)),
    Enter: () => {
      const s = sessions[selected];
      if (s) router.push(`/sessions/${s.id}`);
    },
  });

  const filtered = Boolean(status || type !== "all" || onlyHuman || q);

  return (
    <Panel
      title="Sessions"
      count={sessions.length}
      className="min-h-[420px]"
      actions={
        <span className="hidden text-2xs text-subtle lg:inline">
          <span className="num">j</span>/<span className="num">k</span> to move · <span className="num">↵</span> to open
        </span>
      }
    >
      <div className="flex flex-wrap items-center gap-2 border-b border-line px-3.5 py-2.5">
        <SearchInput value={search} onChange={setSearch} placeholder="Search ref, customer, lane…" className="w-full sm:w-64" />
        <Segmented<TypeFilter>
          value={type}
          onChange={setType}
          options={[
            { value: "all", label: "All" },
            { value: "RFQ", label: "RFQ" },
            { value: "TRACKING", label: "Tracking" },
            { value: "TARIFF", label: "Tariff" },
          ]}
        />
        <Select value={status} onChange={(e) => setStatus(e.target.value as SessionStatus | "")} aria-label="Status" className="w-40">
          <option value="">Any status</option>
          {SESSION_STATUSES.map((s) => (
            <option key={s} value={s}>
              {statusLabel(s)}
            </option>
          ))}
        </Select>
        <button
          onClick={() => setOnlyHuman((v) => !v)}
          aria-pressed={onlyHuman}
          className={cn(
            "inline-flex h-8 items-center gap-1.5 rounded-lg border px-2.5 text-xs font-medium transition-colors",
            onlyHuman ? "border-attn/40 bg-attn/12 text-attn" : "border-line bg-surface-2 text-muted hover:text-fg",
          )}
        >
          <Hand className="size-3.5" /> Needs human
        </button>
        {filtered && (
          <button
            onClick={() => {
              setStatus("");
              setType("all");
              setOnlyHuman(false);
              setSearch("");
            }}
            className="text-xs text-subtle hover:text-fg"
          >
            Clear
          </button>
        )}
      </div>

      <SessionHeaderRow />
      {query.isPending ? (
        <SkeletonRows rows={8} />
      ) : query.isError ? (
        <ErrorState error={query.error} />
      ) : sessions.length === 0 ? (
        <EmptyState
          icon={Inbox}
          title={filtered ? "No sessions match these filters" : "Quiet skies"}
          hint={filtered ? "Try a broader search or clear the filters." : "New RFQs show up here the second they land in the mailbox."}
        />
      ) : (
        <div role="list">
          {sessions.map((s, i) => (
            <SessionRow
              key={s.id}
              s={s}
              selected={i === selected}
              ref={(el) => {
                rows.current[i] = el;
              }}
            />
          ))}
          <LoadMore hasMore={Boolean(query.hasNextPage)} loading={query.isFetchingNextPage} onLoad={() => void query.fetchNextPage()} />
        </div>
      )}
    </Panel>
  );
}
