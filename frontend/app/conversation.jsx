"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import EmailPreview from "./email-preview";
import { spring } from "./lib/motion";
import Icon from "./components/ui/Icon";

const inboxLabels = {
  request: "Customer request",
  tracking: "Tracking question",
  ratedesk: "Rate desk",
  "follow-up": "Follow-up",
};

const outboxLabels = {
  quote: "Customer quote",
  bid: "Bid request",
  counter: "Carrier counteroffer",
  reply: "Team reply",
};

const deliveryLabels = {
  sent: "Sent",
  pending: "Sending",
  uncertain: "Delivery unconfirmed",
  rejected: "Rejected",
};

const timestamp = new Intl.DateTimeFormat(undefined, {
  month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit",
});

function timeline(messages, selected) {
  const events = [];
  const threadId = selected.thread_id ?? selected.root ?? selected.id;
  const fields = messages.find((message) => message.id === threadId)?.fields ?? selected.fields;
  for (const message of messages) {
    if ((message.thread_id ?? message.root ?? message.id) !== threadId) continue;
    events.push({
      key: "in-" + message.id,
      id: message.id,
      root: message.id === threadId,
      label: inboxLabels[message.kind] || (message.stage.startsWith("bid ") ? "Carrier bid" : "Carrier email"),
      address: message.sender || "",
      subject: message.subject,
      body: message.body,
      attachments: message.attachments,
      time: message.received,
    });
    for (const item of message.outgoing || []) {
      const recipient = item.recipient || (item.kind === "bid" ? "" : message.sender);
      events.push({
        key: item.action,
        id: item.message_id,
        label: outboxLabels[item.kind],
        address: recipient || "",
        state: item.state,
        subject: item.subject,
        body: item.body,
        fields,
        outgoing: true,
        time: item.time,
      });
    }
  }
  return events.sort((a, b) =>
    Number(Boolean(b.root)) - Number(Boolean(a.root)) ||
    (Date.parse(a.time) || Infinity) - (Date.parse(b.time) || Infinity) ||
    (a.id ?? Infinity) - (b.id ?? Infinity) || a.key.localeCompare(b.key),
  );
}

const preview = (body) => (body || "").replace(/\s+/g, " ").trim().slice(0, 120);

const GENERIC = new Set(["logistics", "ops", "info", "tracking", "sales", "export", "noreply", "no-reply", "mail", "contact"]);
const words = (text) => text.split(/[-._]+/).filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1));
function displayName(address) {
  if (!address) return "Unknown";
  const [local, domain = ""] = address.split("@");
  const base = GENERIC.has(local.toLowerCase()) ? domain.split(".")[0] : local;
  return words(base).join(" ") || address;
}
const initials = (name) => name.split(" ").slice(0, 2).map((w) => w[0]).join("").toUpperCase();

const clockTime = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

function Avatar({ entry, name }) {
  if (entry.outgoing) return <span className="cv-avatar cv-avatar-us" aria-hidden="true">A</span>;
  return <span className="cv-avatar" aria-hidden="true">{initials(name)}</span>;
}

function describe(entry) {
  const name = entry.outgoing ? "AEIOU" : displayName(entry.address);
  return {
    name,
    counterpart: entry.outgoing ? "to " + displayName(entry.address) : null,
    issue: entry.state && entry.state !== "sent" ? deliveryLabels[entry.state] || entry.state : null,
    full: entry.time ? timestamp.format(new Date(entry.time)) : undefined,
  };
}

function EntryHead({ entry, showPreview }) {
  const { name, counterpart, issue, full } = describe(entry);
  return (
    <>
      <Avatar entry={entry} name={name} />
      <span className="cv-main">
        <span className="cv-line">
          <span className="cv-name">{name}</span>
          {counterpart && <span className="cv-to">{counterpart}</span>}
          {entry.time && <time className="cv-time" dateTime={entry.time} title={full}>{clockTime.format(new Date(entry.time))}</time>}
        </span>
        <span className="cv-line">
          <span className="cv-kind">{entry.label}</span>
          {issue && <span className="cv-issue">{issue}</span>}
          {entry.attachments?.length > 0 && <span className="cv-clip">{entry.attachments.length} attachment{entry.attachments.length > 1 ? "s" : ""}</span>}
        </span>
        {showPreview && <span className="cv-preview">{preview(entry.body)}</span>}
      </span>
    </>
  );
}

function EntryContent({ entry }) {
  return (
    <>
      {entry.outgoing && entry.body != null ? (
        <EmailPreview body={entry.body} fields={entry.fields} />
      ) : (
        <div className="cv-text">{entry.body ?? "The original message text was not recorded."}</div>
      )}
      {entry.attachments?.length > 0 && (
        <div className="cv-files">
          {entry.attachments.map((file) => (
            <a className="cv-file" key={file.id} download={file.filename}
               href={`/api/v1/messages/${entry.id}/attachments/${file.id}`}>
              {file.filename}
            </a>
          ))}
        </div>
      )}
    </>
  );
}

function Entry({ entry, defaultOpen, onExpand }) {
  const [open, setOpen] = useState(defaultOpen);
  const reduce = useReducedMotion();
  return (
    <li className={"cv-item" + (open ? " open" : "")}>
      <button className="cv-row" aria-expanded={open} onClick={() => setOpen(!open)}>
        <EntryHead entry={entry} showPreview={!open} />
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            className="cv-body-wrap"
            initial={reduce ? false : { height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={reduce ? undefined : { height: 0, opacity: 0, transition: { duration: 0.16 } }}
            transition={spring}
          >
            <div className="cv-body">
              <div className="cv-body-head">
                <div className="cv-body-meta">
                  {entry.subject && <div className="cv-subject">{entry.subject}</div>}
                  {entry.address && <div className="cv-address">{(entry.outgoing ? "To " : "From ") + entry.address}</div>}
                </div>
                <button className="cv-expand" onClick={() => onExpand(entry.key)} aria-label="Open in full view" title="Full view">
                  <Icon name="expand" size={15} />
                </button>
              </div>
              <EntryContent entry={entry} />
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </li>
  );
}

/** Desktop full view: thread list on the left, the selected email large on the right. */
function Reader({ entries, initialKey, onClose }) {
  const [key, setKey] = useState(initialKey);
  const reduce = useReducedMotion();
  const index = Math.max(0, entries.findIndex((e) => e.key === key));
  const entry = entries[index];
  const closeRef = useRef(null);
  const move = useCallback(
    (step) => {
      const next = entries[Math.min(entries.length - 1, Math.max(0, index + step))];
      if (next) setKey(next.key);
    },
    [entries, index],
  );
  useEffect(() => {
    closeRef.current?.focus({ preventScroll: true });
    const onKey = (e) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
      } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        move(e.key === "ArrowDown" ? 1 : -1);
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [move, onClose]);
  useEffect(() => {
    document.querySelector(`.rd-list [data-key="${CSS.escape(entry?.key || "")}"]`)?.scrollIntoView({ block: "nearest" });
  }, [entry?.key]);
  if (!entry) return null;
  const { name, full, issue } = describe(entry);
  return (
    <>
      <motion.div className="rd-scrim" onClick={onClose}
                  initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, transition: { duration: 0.15 } }} />
      <motion.section
        className="reader glass glass-strong"
        role="dialog"
        aria-modal="true"
        aria-label="Conversation full view"
        initial={reduce ? false : { opacity: 0, scale: 0.97, y: 10 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={reduce ? undefined : { opacity: 0, scale: 0.98, transition: { duration: 0.16 } }}
        transition={spring}
      >
        <aside className="rd-side">
          <div className="rd-side-head">
            <span className="rd-title">Conversation</span>
            <span className="rd-count">{entries.length}</span>
          </div>
          <ol className="rd-list">
            {entries.map((e) => (
              <li key={e.key} data-key={e.key}>
                <button className={"cv-row rd-row" + (e.key === entry.key ? " active" : "")}
                        aria-current={e.key === entry.key || undefined} onClick={() => setKey(e.key)}>
                  {e.key === entry.key && <motion.span layoutId="rd-highlight" className="rd-highlight" transition={spring} />}
                  <EntryHead entry={e} showPreview />
                </button>
              </li>
            ))}
          </ol>
        </aside>
        <div className="rd-main">
          <header className="rd-head">
            <div className="rd-nav">
              <button className="icon-btn" onClick={() => move(-1)} disabled={index === 0} aria-label="Previous email"><Icon name="up" /></button>
              <button className="icon-btn" onClick={() => move(1)} disabled={index === entries.length - 1} aria-label="Next email"><Icon name="down" /></button>
              <span className="rd-pos">{index + 1} of {entries.length}</span>
            </div>
            <button ref={closeRef} className="icon-btn" onClick={onClose} aria-label="Close full view"><Icon name="close" /></button>
          </header>
          <div className="rd-stage">
            <motion.article
              key={entry.key}
              className="rd-article"
              initial={reduce ? false : { opacity: 0.7 }}
              animate={{ opacity: 1, transition: { duration: 0.14, ease: "easeOut" } }}
            >
              <div className="rd-from">
                <Avatar entry={entry} name={name} />
                <div>
                  <div className="rd-from-line">
                    <strong>{name}</strong>
                    <span className="cv-kind">{entry.label}</span>
                    {issue && <span className="cv-issue">{issue}</span>}
                  </div>
                  <div className="cv-address">{entry.address ? (entry.outgoing ? "To " : "From ") + entry.address : ""}</div>
                </div>
                {full && <time className="rd-time" dateTime={entry.time}>{full}</time>}
              </div>
              <h2 className="rd-subject">{entry.subject || entry.label}</h2>
              <EntryContent entry={entry} />
            </motion.article>
          </div>
        </div>
      </motion.section>
    </>
  );
}

export default function Conversation({ messages, selected }) {
  const entries = timeline(messages, selected);
  const [reader, setReader] = useState(null);
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const close = useCallback(() => setReader(null), []);
  return (
    <>
      <div className="cv-toolbar">
        <button className="cv-full-btn" onClick={() => setReader(entries.at(-1)?.key)} disabled={!entries.length}>
          <Icon name="expand" size={14} /> Full view
        </button>
      </div>
      <ol className="cv" aria-label="Conversation">
        {entries.map((entry, i) => (
          <Entry key={entry.key} entry={entry} onExpand={setReader}
                 defaultOpen={i === entries.length - 1 && entries.length < 3} />
        ))}
      </ol>
      {mounted && createPortal(
        <AnimatePresence>
          {reader && <Reader key="reader" entries={entries} initialKey={reader} onClose={close} />}
        </AnimatePresence>,
        document.body,
      )}
    </>
  );
}
