"use client";

import { cn } from "@/lib/cn";
import { useLive } from "@/lib/live/live-provider";

export function ConnectionIndicator({ compact }: { compact?: boolean }) {
  const { status, retryInMs, eventsReceived } = useLive();
  const label =
    status === "live" ? "Live" : status === "connecting" ? "Connecting" : `Reconnecting${retryInMs ? ` in ${Math.ceil(retryInMs / 1000)}s` : ""}`;
  return (
    <div
      role="status"
      aria-live="polite"
      title={status === "live" ? `Live stream connected · ${eventsReceived} events this session` : "Live stream disconnected, retrying with backoff"}
      className={cn(
        "inline-flex h-7 items-center gap-2 rounded-full border px-2.5 text-xs font-medium",
        status === "live" ? "border-ok/25 bg-ok/8 text-ok" : "border-attn/30 bg-attn/10 text-attn",
      )}
    >
      <span className="relative flex size-2">
        {status === "live" && <span className="absolute inline-flex size-full animate-ping rounded-full bg-ok opacity-50" />}
        <span className={cn("relative inline-flex size-2 rounded-full", status === "live" ? "bg-ok" : "animate-pulse-dot bg-attn")} />
      </span>
      {!compact && label}
    </div>
  );
}
