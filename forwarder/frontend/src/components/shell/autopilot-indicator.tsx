"use client";

import { Bot, FilePenLine } from "lucide-react";
import { useGetAdminStatus } from "@/api/generated/admin/admin";
import { cn } from "@/lib/cn";

/** Always-visible global mode: are live mails answered automatically, or only drafted? */
export function AutopilotIndicator() {
  const { data } = useGetAdminStatus({ query: { retry: false } });
  if (!data) return null;
  const on = data.autopilot;
  return (
    <span
      role="status"
      title={on ? "Live mails are answered automatically" : "Draft mode: the agents only write drafts, a human sends every reply"}
      className={cn(
        "inline-flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-[10px] font-semibold tracking-[0.08em] uppercase",
        on ? "border-accent/25 bg-accent/8 text-accent" : "border-attn/35 bg-attn/12 text-attn",
      )}
    >
      {on ? <Bot className="size-3.5" /> : <FilePenLine className="size-3.5" />}
      <span className="hidden sm:inline">{on ? "Autopilot on" : "Draft mode"}</span>
    </span>
  );
}
