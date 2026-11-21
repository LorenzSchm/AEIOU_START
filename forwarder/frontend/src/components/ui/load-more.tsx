"use client";

import { LoaderCircle } from "lucide-react";
import { useEffect, useRef } from "react";

/** Invisible row at the end of a list that fetches the next cursor page when it scrolls into view. */
export function LoadMore({
  hasMore,
  loading,
  onLoad,
}: {
  hasMore: boolean;
  loading: boolean;
  onLoad: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const onLoadRef = useRef(onLoad);
  useEffect(() => {
    onLoadRef.current = onLoad;
  });

  useEffect(() => {
    const el = ref.current;
    if (!el || !hasMore) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) onLoadRef.current();
      },
      { rootMargin: "400px 0px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [hasMore]);

  if (!hasMore) return null;
  return (
    <div ref={ref} className="flex h-12 items-center justify-center text-xs text-subtle">
      {loading && <LoaderCircle className="size-4 animate-spin" />}
    </div>
  );
}
