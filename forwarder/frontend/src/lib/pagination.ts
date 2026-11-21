import type { InfiniteData } from "@tanstack/react-query";

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

/** Shared infinite-query options for cursor pages `{ items, nextCursor }`. */
export const cursorPaging = {
  initialPageParam: undefined as string | undefined,
  getNextPageParam: (last: { nextCursor: string | null }) => last.nextCursor ?? undefined,
};

export function flatten<T>(data: InfiniteData<Page<T>, unknown> | undefined): T[] {
  return data ? data.pages.flatMap((p) => p.items) : [];
}
