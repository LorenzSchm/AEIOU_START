"use client";

import { ArrowLeft, Bot, CircleCheck, Hand, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import type { SessionSummary } from "@/api/generated/model";
import { Countdown, DeadlineBar } from "@/components/live/countdown";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/fields";
import { addressName, fullDateTime, money, usd } from "@/lib/format";
import { ModeTag, StatusChip, TypeTag, VerdictChip } from "./status";
import { useSessionActions } from "./use-session-action";

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="eyebrow">{label}</span>
      <span className="text-[13px]">{children}</span>
    </div>
  );
}

export function SessionHeader({ s }: { s: SessionSummary }) {
  const { run, pending } = useSessionActions(s.id);
  const [resolving, setResolving] = useState(false);
  const [note, setNote] = useState("");

  return (
    <section className="panel overflow-hidden">
      {s.needsHuman && (
        <div className="flex items-start gap-2.5 border-b border-attn/25 bg-attn/[0.08] px-4 py-2.5 text-[13px]">
          <TriangleAlert className="mt-0.5 size-4 shrink-0 text-attn" />
          <p className="min-w-0 flex-1 text-attn">
            <span className="font-semibold">Needs a human. </span>
            <span className="text-fg/85">{s.needsHumanReason ?? "The agent parked this session."}</span>
          </p>
        </div>
      )}
      <div className="flex flex-wrap items-start gap-x-6 gap-y-4 px-4 py-4">
        <div className="min-w-0 flex-1 basis-[320px]">
          <div className="flex items-center gap-2.5">
            <Link href="/" className="rounded-md p-1 text-subtle hover:bg-surface-3 hover:text-fg" aria-label="Back to live board">
              <ArrowLeft className="size-4" />
            </Link>
            <h1 className="num text-xl font-semibold tracking-tight">{s.publicRef}</h1>
            <StatusChip status={s.status} />
            <TypeTag type={s.type} />
          </div>
          <p className="mt-1.5 truncate text-[15px] font-medium">{s.subject ?? "—"}</p>
          <p className="mt-0.5 text-xs text-muted">
            {addressName(s.customerAddr)} <span className="text-subtle">&lt;{s.customerAddr}&gt;</span> · opened {fullDateTime(s.createdAt)}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {s.autopilot ? (
            <Button variant="secondary" icon={<Hand className="size-3.5" />} loading={pending === "take_over"} onClick={() => run({ action: "take_over" })}>
              Take over
            </Button>
          ) : (
            <Button variant="secondary" icon={<Bot className="size-3.5" />} loading={pending === "release"} onClick={() => run({ action: "release" })}>
              Release to autopilot
            </Button>
          )}
          {s.needsHuman &&
            (resolving ? (
              <form
                className="flex items-center gap-1.5"
                onSubmit={(e) => {
                  e.preventDefault();
                  run({ action: "resolve", ...(note.trim() ? { note: note.trim() } : {}) });
                  setResolving(false);
                  setNote("");
                }}
              >
                <Input autoFocus value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" className="w-48" />
                <Button type="submit" variant="attn" loading={pending === "resolve"}>
                  Resolve
                </Button>
                <Button variant="ghost" onClick={() => setResolving(false)}>
                  Cancel
                </Button>
              </form>
            ) : (
              <Button variant="attn" icon={<CircleCheck className="size-3.5" />} onClick={() => setResolving(true)}>
                Resolve
              </Button>
            ))}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-x-6 gap-y-3 border-t border-line px-4 py-3 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Lane">
          <span className="num">{s.lane ?? "—"}</span>
        </Stat>
        <Stat label="Mode">
          <ModeTag mode={s.mode} />
        </Stat>
        <Stat label={s.quotedAt ? "Response" : "Deadline"}>
          <Countdown session={s} showLabel />
        </Stat>
        <Stat label="Quoted total">
          <span className="num font-semibold">{money(s.quotedTotal, s.quotedCurrency)}</span>
        </Stat>
        <Stat label="LLM cost">
          <span className="num">{usd(s.llmCostUsd)}</span>
        </Stat>
        <Stat label="Board">
          <VerdictChip verdict={s.boardVerdict} />
        </Stat>
      </div>
      {s.deadlineAt && (
        <div className="px-4 pb-3">
          <DeadlineBar session={s} />
          <div className="mt-1 flex justify-between text-[10px] text-subtle">
            <span>request</span>
            <span>2:00 full time points</span>
            <span>6:00 expires</span>
          </div>
        </div>
      )}
      <div className="flex items-center gap-2 border-t border-line px-4 py-2 text-2xs text-subtle">
        {s.autopilot ? (
          <>
            <Bot className="size-3 text-ok" /> Autopilot is handling this session.
          </>
        ) : (
          <>
            <Hand className="size-3 text-attn" /> A human has this session: the agent drafts, you send.
          </>
        )}
        <span className="ml-auto num">
          {s.eventCount} events · {s.mailCount} mails
        </span>
      </div>
    </section>
  );
}
