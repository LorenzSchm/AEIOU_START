"use client";

import { ArrowDownLeft, ArrowLeft, ArrowUpRight, Inbox, MailOpen, Paperclip } from "lucide-react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMemo, useState } from "react";
import { useGetMail, useListMailsInfinite } from "@/api/generated/mails/mails";
import type { ListMailsDirection, ListMailsParams } from "@/api/generated/model";
import { SearchInput, Segmented } from "@/components/ui/fields";
import { LoadMore } from "@/components/ui/load-more";
import { EmptyState, ErrorState, Skeleton, SkeletonRows } from "@/components/ui/states";
import { cn } from "@/lib/cn";
import { addressName, ago } from "@/lib/format";
import { cursorPaging, flatten } from "@/lib/pagination";
import { useDebounced } from "@/lib/use-debounce";
import { useKeys } from "@/lib/use-hotkeys";
import { useNow } from "@/lib/use-now";
import { MailCard, RouteChip } from "./mail-card";

function Reader({ id }: { id: number }) {
  const { data, isPending, isError, error } = useGetMail(id);
  if (isPending)
    return (
      <div className="flex flex-col gap-3 p-5">
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  if (isError) return <ErrorState error={error} />;
  return (
    <div className="p-4 sm:p-5">
      <MailCard mail={data} showSession />
    </div>
  );
}

export function Mailbox() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const selectedId = Number(searchParams.get("id")) || null;
  const [direction, setDirection] = useState<"all" | ListMailsDirection>("all");
  const [search, setSearch] = useState("");
  const q = useDebounced(search.trim(), 250);
  const now = useNow();

  const params = useMemo<ListMailsParams>(
    () => ({ limit: 50, ...(direction !== "all" ? { direction } : {}), ...(q ? { q } : {}) }),
    [direction, q],
  );
  const query = useListMailsInfinite(params, { query: cursorPaging });
  const mails = flatten(query.data);

  const select = (id: number | null) => {
    const sp = new URLSearchParams(searchParams.toString());
    if (id == null) sp.delete("id");
    else sp.set("id", String(id));
    router.replace(`${pathname}${sp.size ? `?${sp}` : ""}`, { scroll: false });
  };
  const index = mails.findIndex((m) => m.id === selectedId);
  useKeys({
    j: () => mails[Math.min(mails.length - 1, index + 1)] && select(mails[Math.min(mails.length - 1, index + 1)].id),
    k: () => index > 0 && select(mails[index - 1].id),
    Escape: () => select(null),
  });

  return (
    <div className="panel grid h-[calc(100dvh-10.5rem)] min-h-[480px] grid-cols-1 overflow-hidden md:grid-cols-[minmax(300px,400px)_minmax(0,1fr)]">
      <div className={cn("flex min-h-0 flex-col border-r border-line", selectedId && "max-md:hidden")}>
        <div className="flex flex-col gap-2 border-b border-line p-3">
          <SearchInput value={search} onChange={setSearch} placeholder="Search subject, body, address…" />
          <Segmented<"all" | ListMailsDirection>
            value={direction}
            onChange={setDirection}
            options={[
              { value: "all", label: "All" },
              { value: "in", label: <><ArrowDownLeft className="size-3" /> Inbound</> },
              { value: "out", label: <><ArrowUpRight className="size-3" /> Outbound</> },
            ]}
          />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {query.isPending ? (
            <SkeletonRows rows={10} />
          ) : query.isError ? (
            <ErrorState error={query.error} />
          ) : mails.length === 0 ? (
            <EmptyState icon={Inbox} title={q ? "No mail matches" : "The mailbox is empty"} />
          ) : (
            <ul>
              {mails.map((m) => (
                <li key={m.id}>
                  <button
                    onClick={() => select(m.id)}
                    aria-current={m.id === selectedId || undefined}
                    className={cn(
                      "relative flex w-full flex-col gap-0.5 border-b border-line px-3.5 py-2.5 text-left transition-colors hover:bg-surface-2",
                      m.id === selectedId && "bg-surface-3",
                    )}
                  >
                    {m.id === selectedId && <span className="absolute inset-y-0 left-0 w-0.5 bg-accent" />}
                    <div className="flex items-center gap-2">
                      {m.direction === "in" ? <ArrowDownLeft className="size-3 shrink-0 text-accent" /> : <ArrowUpRight className="size-3 shrink-0 text-ok" />}
                      <span className="truncate text-[13px] font-medium">{addressName(m.direction === "in" ? m.fromAddr : m.toAddr)}</span>
                      {m.attachmentCount > 0 && <Paperclip className="size-3 shrink-0 text-subtle" />}
                      <span className="num ml-auto shrink-0 text-2xs text-subtle">{ago(m.sentAt, now)}</span>
                    </div>
                    <p className="truncate text-xs">{m.subject}</p>
                    <p className="line-clamp-1 text-xs text-subtle">{m.snippet}</p>
                    <div className="mt-1 flex items-center gap-1.5">
                      {m.sessionRef && <span className="num text-2xs font-semibold text-accent">{m.sessionRef}</span>}
                      <RouteChip type={m.routeType} confidence={m.routeConfidence} />
                    </div>
                  </button>
                </li>
              ))}
              <LoadMore hasMore={Boolean(query.hasNextPage)} loading={query.isFetchingNextPage} onLoad={() => void query.fetchNextPage()} />
            </ul>
          )}
        </div>
      </div>
      <div className={cn("min-h-0 overflow-y-auto bg-bg/40", !selectedId && "max-md:hidden")}>
        {selectedId ? (
          <>
            <button onClick={() => select(null)} className="flex items-center gap-1.5 px-4 pt-3 text-xs text-muted md:hidden">
              <ArrowLeft className="size-3.5" /> All mail
            </button>
            <Reader id={selectedId} />
          </>
        ) : (
          <EmptyState icon={MailOpen} title="Pick a mail to read it" hint="j / k move through the list, Esc closes the reader." className="h-full" />
        )}
      </div>
    </div>
  );
}
