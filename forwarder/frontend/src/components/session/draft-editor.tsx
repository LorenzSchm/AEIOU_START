"use client";

import { FilePenLine, Pencil, Send } from "lucide-react";
import { useState } from "react";
import { useGetSessionDraft } from "@/api/generated/sessions/sessions";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/fields";
import { dateTime } from "@/lib/format";
import { useSessionActions } from "./use-session-action";

/** Shown only when the agent parked a reply for approval. */
export function DraftEditor({ sessionId }: { sessionId: number }) {
  const { data: draft } = useGetSessionDraft(sessionId, { query: { retry: false } });
  const { run, pending } = useSessionActions(sessionId);
  const [editing, setEditing] = useState(false);
  const [body, setBody] = useState("");

  if (!draft) return null;

  return (
    <section className="overflow-hidden rounded-[10px] border border-attn/35 bg-surface shadow-panel">
      <header className="flex flex-wrap items-center gap-2 border-b border-attn/20 bg-attn/[0.06] px-4 py-2.5">
        <FilePenLine className="size-4 text-attn" />
        <h2 className="text-[13px] font-semibold text-attn">Draft waiting for approval</h2>
        <span className="text-2xs text-muted">written {dateTime(draft.createdAt)}</span>
        <div className="ml-auto flex items-center gap-2">
          {!editing && (
            <Button
              size="sm"
              variant="secondary"
              icon={<Pencil className="size-3.5" />}
              onClick={() => {
                setBody(draft.body);
                setEditing(true);
              }}
            >
              Edit
            </Button>
          )}
          {editing ? (
            <>
              <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
                Cancel
              </Button>
              <Button
                size="sm"
                variant="primary"
                icon={<Send className="size-3.5" />}
                loading={pending === "edit_and_send"}
                disabled={!body.trim()}
                onClick={() => run({ action: "edit_and_send", body })}
              >
                Send edited
              </Button>
            </>
          ) : (
            <Button size="sm" variant="attn" icon={<Send className="size-3.5" />} loading={pending === "approve"} onClick={() => run({ action: "approve" })}>
              Approve &amp; send
            </Button>
          )}
        </div>
      </header>
      <div className="border-b border-line px-4 py-2 text-xs text-muted">
        <span className="text-subtle">To </span>
        {draft.to}
        <span className="mx-2 text-subtle">·</span>
        <span className="text-subtle">Subject </span>
        {draft.subject}
      </div>
      {editing ? (
        <div className="p-3">
          <Textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={Math.min(18, body.split("\n").length + 2)}
            className="w-full font-sans text-[13.5px]"
            aria-label="Reply body"
            autoFocus
          />
          <p className="mt-1.5 text-2xs text-subtle">Keep the total and currency exactly as quoted: only the first quote in a thread counts.</p>
        </div>
      ) : (
        <div className="px-4 py-3 text-[13.5px] leading-relaxed whitespace-pre-wrap">{draft.body}</div>
      )}
    </section>
  );
}
