import type { InfiniteData, QueryClient } from "@tanstack/react-query";
import type {
  EventItem,
  PageEventItem,
  ListEventsParams,
  ListSessionsParams,
  SessionDetail,
  SessionSummary,
  PageSessionSummary,
} from "@/api/generated/model";
import { getGetSessionQueryKey } from "@/api/generated/sessions/sessions";
import { byPath, isInfiniteKey, keyParams } from "@/lib/query-keys";

/**
 * Pure cache writers for the live stream. Each one takes the QueryClient and an
 * SSE payload, and patches the affected caches in place where it can, falling
 * back to invalidation where the server owns the shape (candidates, stats, …).
 */

const FEED_CAP = 400;

function matchesSessionFilter(s: SessionSummary, p: ListSessionsParams | undefined): boolean {
  if (!p) return true;
  if (p.status && s.status !== p.status) return false;
  if (p.type && s.type !== p.type) return false;
  if (p.needsHuman !== undefined && s.needsHuman !== p.needsHuman) return false;
  if (p.q) {
    const hay = [s.publicRef, s.customerAddr, s.subject, s.lane, s.lastEventSummary].join(" ").toLowerCase();
    if (!hay.includes(p.q.toLowerCase())) return false;
  }
  return true;
}

const byLastEventDesc = (a: SessionSummary, b: SessionSummary) =>
  b.lastEventAt.localeCompare(a.lastEventAt) || b.id - a.id;

/** Upsert into every cached session list (respecting its filter) and the detail cache. */
export function applySession(qc: QueryClient, session: SessionSummary): void {
  for (const query of qc.getQueryCache().findAll({ predicate: byPath("/api/sessions") })) {
    const key = query.queryKey;
    const params = keyParams<ListSessionsParams>(key);
    const keep = matchesSessionFilter(session, params);

    if (isInfiniteKey(key)) {
      qc.setQueryData<InfiniteData<PageSessionSummary, unknown>>(key, (old) => {
        if (!old || old.pages.length === 0) return old;
        const pages = old.pages.map((p) => ({ ...p, items: p.items.filter((s) => s.id !== session.id) }));
        if (keep) {
          const first = pages[0];
          pages[0] = { ...first, items: [session, ...first.items].sort(byLastEventDesc) };
        }
        return { ...old, pages };
      });
    } else {
      qc.setQueryData<PageSessionSummary>(key, (old) => {
        if (!old) return old;
        const items = old.items.filter((s) => s.id !== session.id);
        return { ...old, items: keep ? [session, ...items].sort(byLastEventDesc) : items };
      });
    }
  }

  qc.setQueryData<SessionDetail>(getGetSessionQueryKey(session.id), (old) =>
    old ? { ...old, session } : old,
  );
}

function appendAscending(old: InfiniteData<PageEventItem, unknown> | undefined, event: EventItem) {
  if (!old || old.pages.length === 0) return old;
  if (old.pages.some((p) => p.items.some((e) => e.id === event.id))) return old;
  const lastIndex = old.pages.length - 1;
  const last = old.pages[lastIndex];
  // More pages still to load on the server: the event will arrive with them.
  if (last.nextCursor) return old;
  const pages = [...old.pages];
  pages[lastIndex] = { ...last, items: [...last.items, event] };
  return { ...old, pages };
}

function prependDescending(old: InfiniteData<PageEventItem, unknown> | undefined, event: EventItem) {
  if (!old || old.pages.length === 0) return old;
  const first = old.pages[0];
  if (first.items.some((e) => e.id === event.id)) return old;
  const items = [event, ...first.items].slice(0, FEED_CAP);
  return { ...old, pages: [{ ...first, items }, ...old.pages.slice(1)] };
}

/** Append to the open session timeline and the global feed. */
export function applyEvent(qc: QueryClient, event: EventItem): void {
  if (event.sessionId != null) {
    const path = `/api/sessions/${event.sessionId}/events`;
    for (const query of qc.getQueryCache().findAll({ predicate: byPath(path) })) {
      if (isInfiniteKey(query.queryKey)) {
        qc.setQueryData<InfiniteData<PageEventItem, unknown>>(query.queryKey, (old) => appendAscending(old, event));
      }
    }
  }

  for (const query of qc.getQueryCache().findAll({ predicate: byPath("/api/events") })) {
    const kind = keyParams<ListEventsParams>(query.queryKey)?.kind;
    if (kind && !event.kind.startsWith(kind)) continue;
    if (isInfiniteKey(query.queryKey)) {
      qc.setQueryData<InfiniteData<PageEventItem, unknown>>(query.queryKey, (old) => prependDescending(old, event));
    }
  }
}

/** Which server-owned caches an event kind makes stale. */
export function invalidationsFor(event: EventItem): string[] {
  const s = event.sessionId;
  const session = s != null ? [`/api/sessions/${s}`] : [];
  const k = event.kind;
  const out: string[] = [];

  if (k.startsWith("mail.")) {
    out.push("/api/mails", "/api/mails/*");
    if (s != null) out.push(`/api/sessions/${s}/mails`, `/api/sessions/${s}/draft`);
  }
  if (k.startsWith("pricing.") || k.startsWith("bid.") || k.startsWith("route.") || k.startsWith("quote.")) {
    out.push(...session);
    if (s != null) out.push(`/api/sessions/${s}/draft`);
  }
  if (k.startsWith("booking.") || k.startsWith("portal.booking")) {
    out.push("/api/bookings", "/api/shipments/active", ...session);
  }
  if (k.startsWith("tariff.")) out.push("/api/tariffs", "/api/tariffs/*");
  if (k.startsWith("llm.")) out.push("/api/llm-calls");
  if (k.startsWith("board.")) out.push("/api/board");
  if (k === "human.setting_changed" || k.startsWith("setting.") || k.startsWith("admin.")) out.push("/api/settings", "/api/admin/status");
  if (k.startsWith("human.")) out.push(...session);
  return out;
}

export function invalidatePaths(qc: QueryClient, paths: string[]): void {
  if (paths.length === 0) return;
  void qc.invalidateQueries({ predicate: byPath(...paths) });
}

/** The globe keeps its own key; keep it in sync with bookings. */
export function invalidateGlobe(qc: QueryClient, event: EventItem): void {
  if (event.kind.startsWith("booking.")) void qc.invalidateQueries({ queryKey: ["shipments"] });
}
