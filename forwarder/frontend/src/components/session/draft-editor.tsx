"use client";

import { ChevronRight, FilePenLine, Pencil, Send } from "lucide-react";
import { useState } from "react";
import { useGetSessionDraft } from "@/api/generated/sessions/sessions";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/fields";
import { dateTime } from "@/lib/format";
import { useSessionActions } from "./use-session-action";

/** Only exists in draft mode or when a human took the session over. Collapsed by default. */
export function DraftEditor({ sessionId }: { sessionId: number }) {
  const { data: draft } = useGetSessionDraft(sessionId, {
    query: { retry: false },
  });
  const { run, pending } = useSessionActions(sessionId);
  const [editing, setEditing] = useState(false);
  const [open, setOpen] = useState(false);
  const [body, setBody] = useState("");

  if (!draft) return null;

  return (
    <section className="panel overflow-hidden">
      <header className="flex flex-wrap items-center gap-2 px-4 py-2">
        <button
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          className="flex min-w-0 flex-1 items-center gap-2 text-left"
        >
          <ChevronRight
            className={`size-3.5 text-subtle transition-transform ${open ? "rotate-90" : ""}`}
          />
          <FilePenLine className="size-3.5 text-muted" />
          <h2 className="text-[13px] font-semibold">Draft</h2>
          <span
            className="truncate text-xs text-subtle"
            title={dateTime(draft.createdAt)}
          >
            {draft.subject}
          </span>
        </button>
        <div className="flex items-center gap-2">
          {open && !editing && (
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
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setEditing(false)}
              >
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
            <Button
              size="sm"
              variant="secondary"
              icon={<Send className="size-3.5" />}
              loading={pending === "approve"}
              onClick={() => run({ action: "approve" })}
            >
              Send
            </Button>
          )}
        </div>
      </header>
      {open && (
        <>
          <div className="border-y border-line px-4 py-2 text-xs text-muted">
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
            </div>
          ) : (
            <div className="px-4 py-3 text-[13.5px] leading-relaxed whitespace-pre-wrap">
              {draft.body}
            </div>
          )}
        </>
      )}
    </section>
  );
}
