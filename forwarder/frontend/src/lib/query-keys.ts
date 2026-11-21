import type { Query, QueryKey } from "@tanstack/react-query";

/**
 * orval keys look like ["/api/sessions", params] or ["infinite", "/api/sessions", params].
 * These helpers let the live layer target caches by API path without knowing params.
 */
export function keyPath(key: QueryKey): string | null {
  const first = key[0] === "infinite" ? key[1] : key[0];
  return typeof first === "string" ? first : null;
}

export function keyParams<T>(key: QueryKey): T | undefined {
  const p = key[0] === "infinite" ? key[2] : key[1];
  return p && typeof p === "object" ? (p as T) : undefined;
}

export const isInfiniteKey = (key: QueryKey): boolean => key[0] === "infinite";

/** Matches API paths exactly, or by prefix when the pattern ends with "*". */
export function byPath(...patterns: string[]) {
  return (q: Query): boolean => {
    const p = keyPath(q.queryKey);
    if (!p) return false;
    return patterns.some((pattern) =>
      pattern.endsWith("*") ? p.startsWith(pattern.slice(0, pattern.length - 1)) : p === pattern,
    );
  };
}
