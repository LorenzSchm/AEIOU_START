"use client";

import { ArrowDownLeft, ArrowUpRight, Paperclip } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import { getDownloadAttachmentUrl } from "@/api/generated/mails/mails";
import type { MailDetail } from "@/api/generated/model";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/cn";
import { addressName, bytes, fullDateTime, humanize, time } from "@/lib/format";

export function RouteChip({ type, confidence }: { type: string | null; confidence: number | null }) {
  if (!type) return null;
  const low = confidence != null && confidence < 0.75;
  return (
    <Badge tone={low ? "attn" : "violet"} title={confidence != null ? `Router confidence ${confidence.toFixed(2)}` : undefined}>
      {humanize(type)}
      {confidence != null && confidence < 1 && <span className="num opacity-80">{confidence.toFixed(2)}</span>}
    </Badge>
  );
}

/** A readable email: who, what, when, the body as written, and its attachments. */
export function MailCard({
  mail,
  actions,
  showSession,
  compact,
}: {
  mail: MailDetail;
  actions?: ReactNode;
  showSession?: boolean;
  compact?: boolean;
}) {
  const inbound = mail.direction === "in";
  return (
    <article
      className={cn(
        "overflow-hidden rounded-lg border bg-surface",
        inbound ? "border-line" : "border-ok/20",
      )}
    >
      <header className={cn("flex flex-wrap items-start gap-x-3 gap-y-1 border-b px-3.5 py-2.5", inbound ? "border-line bg-surface-2" : "border-ok/15 bg-ok/[0.04]")}>
        <span
          className={cn("mt-0.5 grid size-6 shrink-0 place-items-center rounded-full", inbound ? "bg-accent/12 text-accent" : "bg-ok/12 text-ok")}
          title={inbound ? "Inbound" : "Outbound"}
        >
          {inbound ? <ArrowDownLeft className="size-3.5" /> : <ArrowUpRight className="size-3.5" />}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-semibold">{mail.subject}</p>
          <p className="truncate text-xs text-muted">
            <span className="text-fg/80">{addressName(mail.fromAddr)}</span>
            <span className="text-subtle"> &lt;{mail.fromAddr}&gt;</span>
            <span className="text-subtle"> → </span>
            {mail.toAddr}
          </p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <time className="num text-2xs text-subtle" dateTime={mail.sentAt} title={fullDateTime(mail.sentAt)}>
            {time(mail.sentAt)}
          </time>
          <div className="flex items-center gap-1.5">
            {showSession && mail.sessionRef && mail.sessionId != null && (
              <Link href={`/sessions/${mail.sessionId}`} className="num text-2xs font-semibold text-accent hover:underline">
                {mail.sessionRef}
              </Link>
            )}
            <RouteChip type={mail.routeType} confidence={mail.routeConfidence} />
          </div>
        </div>
      </header>
      <div className={cn("px-4 py-3 text-[13.5px] leading-relaxed whitespace-pre-wrap text-fg/90", compact && "max-h-72 overflow-y-auto")}>
        {mail.body}
      </div>
      {(mail.attachments.length > 0 || actions) && (
        <footer className="flex flex-wrap items-center gap-2 border-t border-line px-3.5 py-2">
          {mail.attachments.map((a) => (
            <a
              key={a.id}
              href={getDownloadAttachmentUrl(a.id)}
              download={a.filename}
              className="inline-flex items-center gap-1.5 rounded-md border border-line bg-surface-2 px-2 py-1 text-xs hover:border-line-strong hover:text-accent"
            >
              <Paperclip className="size-3" />
              {a.filename}
              {a.size != null && <span className="num text-2xs text-subtle">{bytes(a.size)}</span>}
            </a>
          ))}
          {actions && <div className="ml-auto flex items-center gap-2">{actions}</div>}
        </footer>
      )}
    </article>
  );
}
