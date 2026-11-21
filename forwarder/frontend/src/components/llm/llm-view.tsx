"use client";

import { Bot } from "lucide-react";
import Link from "next/link";
import { useMemo, useState } from "react";
import { useGetLlmCall, useListLlmCallsInfinite } from "@/api/generated/llm/llm";
import type { LlmCall } from "@/api/generated/model";
import { Badge } from "@/components/ui/badge";
import { Drawer } from "@/components/ui/drawer";
import { JsonView } from "@/components/ui/json-view";
import { LoadMore } from "@/components/ui/load-more";
import { Facts, Panel } from "@/components/ui/panel";
import { EmptyState, ErrorState, Skeleton, SkeletonRows } from "@/components/ui/states";
import { cn } from "@/lib/cn";
import { fullDateTime, int, ms, time, usd } from "@/lib/format";
import { cursorPaging, flatten } from "@/lib/pagination";

interface ChatMessage {
  role: string;
  content: string;
}

function messagesOf(value: unknown): ChatMessage[] | null {
  if (!value || typeof value !== "object") return null;
  const v = value as { messages?: unknown; choices?: unknown };
  const list = Array.isArray(v.messages)
    ? v.messages
    : Array.isArray(v.choices)
      ? v.choices.map((c) => (c as { message?: unknown }).message)
      : null;
  if (!list) return null;
  const out = list.filter(
    (m): m is ChatMessage => !!m && typeof m === "object" && typeof (m as ChatMessage).role === "string" && typeof (m as ChatMessage).content === "string",
  );
  return out.length ? out : null;
}

function Messages({ messages }: { messages: ChatMessage[] }) {
  return (
    <div className="flex flex-col gap-2">
      {messages.map((m, i) => (
        <div key={i} className="rounded-lg border border-line bg-surface-2">
          <div className={cn("eyebrow border-b border-line px-3 py-1.5", m.role === "assistant" ? "!text-violet" : m.role === "system" ? "!text-cyan" : "")}>{m.role}</div>
          <pre className="max-h-80 overflow-auto px-3 py-2 font-sans text-[13px] leading-relaxed whitespace-pre-wrap">{m.content}</pre>
        </div>
      ))}
    </div>
  );
}

function CallDetail({ id }: { id: number }) {
  const { data, isPending, isError, error } = useGetLlmCall(id);
  if (isPending) return <div className="flex flex-col gap-3 p-5"><Skeleton className="h-24" /><Skeleton className="h-64" /></div>;
  if (isError) return <ErrorState error={error} />;
  const req = messagesOf(data.request);
  const res = messagesOf(data.response);
  return (
    <div className="flex flex-col gap-5 p-5">
      <Facts
        items={[
          { label: "Model", value: data.model, mono: true },
          { label: "Purpose", value: data.purpose },
          { label: "Tokens", value: `${int(data.promptTokens)} prompt · ${int(data.completionTokens)} completion`, mono: true },
          { label: "Cost", value: usd(data.costUsd), mono: true },
          { label: "Latency", value: ms(data.latencyMs), mono: true },
          { label: "At", value: fullDateTime(data.createdAt), mono: true },
          {
            label: "Session",
            value: data.sessionId != null ? <Link className="text-accent hover:underline" href={`/sessions/${data.sessionId}`}>#{data.sessionId}</Link> : "—",
          },
        ]}
      />
      <section>
        <h3 className="eyebrow mb-2">Prompt</h3>
        {req ? <Messages messages={req} /> : <JsonView value={data.request ?? null} />}
      </section>
      <section>
        <h3 className="eyebrow mb-2">Response</h3>
        {res ? <Messages messages={res} /> : <JsonView value={data.response ?? null} />}
      </section>
      <details>
        <summary className="cursor-pointer text-xs text-subtle hover:text-fg">Raw request / response JSON</summary>
        <div className="mt-2 flex flex-col gap-2">
          <JsonView value={data.request ?? null} />
          <JsonView value={data.response ?? null} />
        </div>
      </details>
    </div>
  );
}

interface Group {
  key: string;
  purpose: string;
  model: string;
  calls: number;
  cost: number;
  tokens: number;
  latency: number[];
}

function summarize(calls: LlmCall[]): Group[] {
  const map = new Map<string, Group>();
  for (const c of calls) {
    const key = `${c.purpose}|${c.model}`;
    const g = map.get(key) ?? { key, purpose: c.purpose, model: c.model, calls: 0, cost: 0, tokens: 0, latency: [] };
    g.calls++;
    g.cost += Number(c.costUsd ?? 0);
    g.tokens += (c.promptTokens ?? 0) + (c.completionTokens ?? 0);
    if (c.latencyMs != null) g.latency.push(c.latencyMs);
    map.set(key, g);
  }
  return [...map.values()].sort((a, b) => b.cost - a.cost);
}

const TH = "eyebrow px-3 py-2 text-left font-medium whitespace-nowrap";
const TD = "px-3 py-2 whitespace-nowrap";

export function LlmView() {
  const q = useListLlmCallsInfinite({ limit: 100 }, { query: cursorPaging });
  const calls = flatten(q.data);
  const groups = useMemo(() => summarize(calls), [calls]);
  const [open, setOpen] = useState<number | null>(null);
  const totalCost = groups.reduce((a, g) => a + g.cost, 0);

  return (
    <div className="flex flex-col gap-4">
      <Panel title="By purpose and model" icon={<Bot />} actions={<span className="num text-2xs text-subtle">{usd(totalCost)} over {calls.length} loaded calls</span>}>
        {q.isPending ? (
          <SkeletonRows rows={3} />
        ) : groups.length === 0 ? (
          <EmptyState icon={Bot} title="No LLM calls yet" className="py-8" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead className="border-b border-line">
                <tr>
                  <th className={TH}>Purpose</th>
                  <th className={TH}>Model</th>
                  <th className={`${TH} text-right`}>Calls</th>
                  <th className={`${TH} text-right`}>Tokens</th>
                  <th className={`${TH} text-right`}>Cost</th>
                  <th className={`${TH} text-right`}>Avg cost</th>
                  <th className={`${TH} text-right`}>Avg latency</th>
                  <th className={`${TH} w-1/4`}>Share of spend</th>
                </tr>
              </thead>
              <tbody>
                {groups.map((g) => (
                  <tr key={g.key} className="border-b border-line last:border-b-0">
                    <td className={TD}>{g.purpose}</td>
                    <td className={`${TD} num text-violet`}>{g.model}</td>
                    <td className={`${TD} num text-right`}>{g.calls}</td>
                    <td className={`${TD} num text-right text-muted`}>{int(g.tokens)}</td>
                    <td className={`${TD} num text-right`}>{usd(g.cost)}</td>
                    <td className={`${TD} num text-right text-muted`}>{usd(g.cost / g.calls)}</td>
                    <td className={`${TD} num text-right text-muted`}>{ms(Math.round(g.latency.reduce((a, b) => a + b, 0) / Math.max(1, g.latency.length)))}</td>
                    <td className={TD}>
                      <div className="h-1.5 overflow-hidden rounded-full bg-surface-3">
                        <div className="h-full rounded-full bg-violet" style={{ width: `${totalCost ? (g.cost / totalCost) * 100 : 0}%` }} />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel title="Calls" icon={<Bot />} count={calls.length}>
        {q.isPending ? (
          <SkeletonRows rows={8} />
        ) : q.isError ? (
          <ErrorState error={q.error} />
        ) : calls.length === 0 ? (
          <EmptyState icon={Bot} title="No LLM calls yet" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead className="border-b border-line">
                <tr>
                  <th className={TH}>Time</th>
                  <th className={TH}>Purpose</th>
                  <th className={TH}>Model</th>
                  <th className={TH}>Session</th>
                  <th className={`${TH} text-right`}>Prompt</th>
                  <th className={`${TH} text-right`}>Completion</th>
                  <th className={`${TH} text-right`}>Cost</th>
                  <th className={`${TH} text-right`}>Latency</th>
                  <th className={TH}>Result</th>
                </tr>
              </thead>
              <tbody>
                {calls.map((c) => (
                  <tr key={c.id} onClick={() => setOpen(c.id)} className="cursor-pointer border-b border-line last:border-b-0 hover:bg-surface-2">
                    <td className={`${TD} num text-muted`}>{time(c.createdAt)}</td>
                    <td className={TD}>
                      <button className="text-left hover:text-accent" onClick={() => setOpen(c.id)}>
                        {c.purpose}
                      </button>
                    </td>
                    <td className={`${TD} num text-violet`}>{c.model}</td>
                    <td className={`${TD} num text-muted`}>{c.sessionId != null ? `#${c.sessionId}` : "—"}</td>
                    <td className={`${TD} num text-right text-muted`}>{int(c.promptTokens)}</td>
                    <td className={`${TD} num text-right text-muted`}>{int(c.completionTokens)}</td>
                    <td className={`${TD} num text-right`}>{usd(c.costUsd)}</td>
                    <td className={`${TD} num text-right ${c.latencyMs != null && c.latencyMs > 5000 ? "text-attn" : "text-muted"}`}>{ms(c.latencyMs)}</td>
                    <td className={TD}>{c.success ? <Badge tone="ok">ok</Badge> : <Badge tone="danger">failed</Badge>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <LoadMore hasMore={Boolean(q.hasNextPage)} loading={q.isFetchingNextPage} onLoad={() => void q.fetchNextPage()} />
          </div>
        )}
      </Panel>

      <Drawer open={open != null} onClose={() => setOpen(null)} title={`LLM call #${open ?? ""}`}>
        {open != null && <CallDetail id={open} />}
      </Drawer>
    </div>
  );
}
