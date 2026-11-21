"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import Conversation from "../../conversation";
import EmailPreview from "../../email-preview";
import Icon from "../ui/Icon";
import { Chip, CountdownRing, RouteBadge, Section } from "../ui/primitives";
import OfferBars from "./OfferBars";
import Select from "../ui/Select";
import Timeline from "./Timeline";
import { deriveProcess } from "../../lib/process";
import { api } from "../../lib/api";
import { clock, eur, kindLabel, needsAttention, secondsLeft, statusOf, transitLabel } from "../../lib/format";
import { spring } from "../../lib/motion";

/** Stable sheet frame: slides in once; switching cases only crossfades the content. */
export default function CaseSheet(props) {
  const { m } = props;
  const reduce = useReducedMotion();
  // Only the case the sheet opened with plays the intro; switching cases swaps content calmly.
  const firstId = useRef(m.id);
  return (
    <motion.aside
      className="sheet glass glass-strong"
      role="dialog"
      aria-modal="false"
      aria-label={"Case " + m.id}
      initial={{ opacity: 0, x: 32, scale: 0.985 }}
      animate={{ opacity: 1, x: 0, scale: 1 }}
      // Dismissal mirrors the entrance (same path, scale, opacity) — just faster and
      // accelerating out, as HIG/Material recommend for exits.
      exit={{ opacity: 0, x: 32, scale: 0.985, transition: { duration: 0.24, ease: [0.4, 0, 1, 1] } }}
      transition={spring}
    >
      <motion.div
        key={m.id}
        className="sheet-inner"
        initial={reduce || m.id === firstId.current ? false : { opacity: 0.7 }}
        animate={{ opacity: 1, transition: { duration: 0.14, ease: "easeOut" } }}
      >
        <CaseContent {...props} intro={m.id === firstId.current} />
      </motion.div>
    </motion.aside>
  );
}

function CaseContent({ m, messages, directory, refresh, onClose, now, intro, onDirtyChange }) {
  const [fields, setFields] = useState(m.fields || {});
  const [dirty, setDirty] = useState(false);
  // Report unsaved edits upward so navigation can ask before discarding them.
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);
  const [bid, setBid] = useState({ airline: "", air_total_eur: "", transit_days: "" });
  const [recipient, setRecipient] = useState("");
  const [reply, setReply] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);
  const closeRef = useRef(null);

  useEffect(() => {
    // Esc closes the case, but never while typing: that would silently discard manual overrides.
    const onKey = (e) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      const t = e.target;
      if (t instanceof HTMLElement && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) {
        t.blur();
        return;
      }
      onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const remaining = secondsLeft(m, now);
  const closed = Boolean(m.delivery) || (remaining !== null && remaining <= 0);
  const request = m.kind === "request";
  const proposal = m.proposal;
  const status = statusOf(m, now);
  const process = useMemo(() => (request ? deriveProcess(m, messages, { now, directory }) : null), [m, messages, request, directory, now]);
  const thread = messages.filter((x) => (x.thread_id ?? x.id) === (m.thread_id ?? m.id));
  const mailCount = thread.length + thread.reduce((n, x) => n + (x.outgoing?.length || 0), 0);

  const suitable = directory.carriers.filter((c) =>
    c.serves.split(" ").includes(c.kind === "trucker" ? fields.pickup_zone : fields.destination),
  );
  const set = (key, value) => {
    setDirty(true);
    setFields((old) => {
      const updated = { ...old, [key]: value };
      if (key === "pickup_zone" && directory.zones[value]) updated.origin = directory.zones[value];
      return updated;
    });
  };
  const shipment = () => ({
    ...fields,
    pickup_zone: fields.pickup_zone || null,
    pickup_eur: fields.pickup_eur === "" ? null : fields.pickup_eur,
    pickup_days: Number(fields.pickup_days || 0),
  });
  async function act(endpoint, payload, done, success) {
    setNotice(null);
    setBusy(true);
    try {
      await api("/messages/" + m.id + "/" + endpoint, payload);
      done?.();
      setNotice({ tone: "ok", text: success });
      await refresh();
    } catch (e) {
      setNotice({ tone: "bad", text: e.message });
    } finally {
      setBusy(false);
    }
  }
  const input = (key, title, type = "text", maxLength) => (
    <label className="field">
      <span>{title}</span>
      <input
        type={type}
        inputMode={type === "number" ? "decimal" : undefined}
        step={type === "number" ? "any" : undefined}
        min={type === "number" ? "0" : undefined}
        value={fields[key] ?? ""}
        maxLength={maxLength}
        onChange={(e) => set(key, type === "text" ? e.target.value.toUpperCase() : e.target.value)}
      />
    </label>
  );

  const canSend = proposal?.offer && !busy && !closed && !dirty;
  // Anything that needs a human opens the override controls straight away.
  const attention = Boolean(m.review_reason) || needsAttention(m);
  const canBook = m.delivery === "sent" && proposal?.offer?.rate_id && !m.booking;

  return (
    <>
      <header className="sheet-head">
        <div className="sheet-title">
          <div className="eyebrow">
            {kindLabel[m.kind] || m.kind} · #{m.id} · {clock(m.received)}
          </div>
          {request ? (
            <RouteBadge origin={fields.origin} destination={fields.destination} size="lg" />
          ) : (
            <h2>{m.subject || "(No subject)"}</h2>
          )}
          <div className="sheet-sub" title={m.subject}>
            {m.sender || "Unknown sender"}
          </div>
        </div>
        <div className="sheet-status">
          {request && m.received && <CountdownRing received={m.received} now={now} size={52} done={Boolean(m.delivery)} />}
          <Chip tone={status.tone}>{status.label}</Chip>
        </div>
        <button ref={closeRef} className="icon-btn" aria-label="Close case" onClick={onClose}>
          <Icon name="close" />
        </button>
      </header>

      {m.review_reason && (
        <div className="banner banner-warn" role="status">
          <Icon name="alert" size={16} />
          <span>{m.review_reason}</span>
        </div>
      )}

      <div className="sheet-body">
        {request && proposal?.offer && (
          <div className="hero-quote">
            <div>
              <div className="eyebrow">All-in quote</div>
              <div className="amount">{eur(proposal.offer.total_eur)}</div>
            </div>
            <dl className="hero-facts">
              <div><dt>Carrier</dt><dd>{proposal.offer.airline}</dd></div>
              <div><dt>Transit</dt><dd>{transitLabel(proposal.offer.days)}</dd></div>
              <div><dt>Source</dt><dd>{proposal.offer.source}</dd></div>
              {proposal.pickup_eur != null && (
                <div><dt>Pickup</dt><dd>{eur(proposal.pickup_eur)} · {proposal.trucker || "operator"}</dd></div>
              )}
              {m.booking && <div><dt>Booking</dt><dd className="mono">{m.booking.booking_reference}</dd></div>}
            </dl>
          </div>
        )}

        {request && (
          <Section title="Process" icon="clock" defaultOpen summary={process?.outcome}>
            <Timeline steps={process?.steps || []} intro={intro} />
          </Section>
        )}

        {request && proposal && (
          <Section
            title="Offers compared"
            icon="bars"
            badge={proposal.alternatives?.length || 0}
            summary={proposal.offer ? "Winner " + proposal.offer.airline : "No complete offer"}
          >
            {proposal.notes?.length > 0 && (
              <ul className="notes">{proposal.notes.map((n) => <li key={n}>{n}</li>)}</ul>
            )}
            <OfferBars offers={proposal.alternatives} winner={proposal.offer} />
          </Section>
        )}

        {request && (
          <Section
            title="Shipment"
            icon="box"
            defaultOpen={attention}
            summary={[fields.weight_kg && fields.weight_kg + " kg", fields.volume_cbm && fields.volume_cbm + " m³", fields.preference]
              .filter(Boolean).join(" · ")}
          >
            <fieldset disabled={busy || closed}>
              <div className="grid">
                {input("origin", "Origin", "text", 3)}
                {input("destination", "Destination", "text", 3)}
                {input("weight_kg", "Weight · kg", "number")}
                {input("volume_cbm", "Volume · m³", "number")}
                <div className="field">
                  <span>Priority</span>
                  <Select
                    ariaLabel="Priority"
                    value={fields.preference || ""}
                    onChange={(v) => set("preference", v)}
                    disabled={busy || closed}
                    options={[
                      { value: "cheapest", label: "Cheapest" },
                      { value: "fastest", label: "Fastest" },
                    ]}
                  />
                </div>
                {input("pickup_zone", "Pickup zone", "text", 2)}
                {fields.pickup_zone && (
                  <>
                    {input("pickup_eur", "Truck · EUR", "number")}
                    {input("pickup_days", "Truck · days", "number")}
                  </>
                )}
              </div>
              <div className="actions">
                {m.review_reason && (
                  <button className="btn" onClick={() => act("resume", shipment(), () => setDirty(false), "Returned to automatic pricing.")}>
                    Resume automatic
                  </button>
                )}
                <button
                  className="btn btn-tinted"
                  onClick={() =>
                    act(
                      "price",
                      { shipment: shipment(), carrier_bid: Object.values(bid).some(Boolean) ? bid : null },
                      () => setDirty(false),
                      "Pricing updated.",
                    )
                  }
                >
                  {busy ? "Working…" : "Compare rates"}
                </button>
              </div>
              {dirty && !closed && <p className="hint">Compare rates again to apply your edits.</p>}
            </fieldset>
          </Section>
        )}

        {request && (
          <Section title="Carrier bids" icon="plane" defaultOpen={attention} badge={Object.keys(m.bid_requests || {}).length}
                   summary={Object.values(m.bid_requests || {}).filter((v) => v === "priced").length + " priced"}>
            {Object.keys(m.bid_requests || {}).length > 0 && (
              <ul className="bid-list">
                {Object.entries(m.bid_requests).map(([email, st]) => {
                  const c = directory.carriers.find((x) => x.email === email);
                  return (
                    <li key={email}>
                      <Icon name={c?.kind === "trucker" ? "truck" : "plane"} size={14} />
                      <span>{c?.name || email}</span>
                      <Chip tone={st === "priced" ? "ok" : st === "unreadable" ? "warn" : "muted"}>{st}</Chip>
                    </li>
                  );
                })}
              </ul>
            )}
            <fieldset disabled={busy || closed}>
              <div className="bid-row">
                <div className="field">
                  <span>Invite carrier</span>
                  <Select
                    ariaLabel="Invite carrier"
                    placeholder="Choose carrier"
                    value={recipient}
                    onChange={setRecipient}
                    disabled={busy || closed}
                    options={suitable.map((c) => ({ value: c.email, label: c.name, hint: c.kind }))}
                  />
                </div>
                <button
                  className="btn"
                  disabled={!recipient || Boolean(m.bid_requests?.[recipient])}
                  onClick={() => act("bids", { shipment: shipment(), email: recipient }, null, "Bid request sent.")}
                >
                  {m.bid_requests?.[recipient] || "Request bid"}
                </button>
              </div>
              <div className="grid">
                {[["airline", "Manual bid · airline"], ["air_total_eur", "Air total · EUR"], ["transit_days", "Transit · days"]].map(([k, label]) => (
                  <label className="field" key={k}>
                    <span>{label}</span>
                    <input value={bid[k]} onChange={(e) => { setDirty(true); setBid({ ...bid, [k]: e.target.value }); }} />
                  </label>
                ))}
              </div>
            </fieldset>
          </Section>
        )}

        {request && proposal?.body && !m.delivery && (
          <Section title="Quote email" icon="mail" summary="Preview before sending">
            <EmailPreview body={proposal.body} fields={fields} />
          </Section>
        )}

        <Section title="Conversation" icon="mail" badge={mailCount} defaultOpen={!request} summary="All emails in this thread">
          <Conversation messages={messages} selected={m} />
        </Section>

        {!request && (
          <Section title="Reply" icon="reply" defaultOpen={m.stage === "tracking review"}>
            <label className="field">
              <span>Reply in thread</span>
              <textarea rows={5} value={reply} onChange={(e) => setReply(e.target.value)} />
            </label>
            {reply.trim() && <EmailPreview body={reply} fields={fields} />}
            <div className="actions">
              <button
                className="btn btn-primary"
                disabled={busy || !reply.trim() || Boolean(m.reply_delivery)}
                onClick={() => act("reply", { body: reply }, () => setReply(""), "Reply sent.")}
              >
                {m.reply_delivery || "Send reply"}
              </button>
            </div>
          </Section>
        )}

        {["uncertain", "pending"].includes(m.delivery) && (
          <div className="banner banner-warn">
            <Icon name="alert" size={16} />
            <span>Delivery unconfirmed. Check the team mailbox before resending manually.</span>
          </div>
        )}
      </div>

      {request && proposal?.offer && (canSend || canBook || !m.delivery || notice) && (
        <footer className="sheet-foot">
          {notice && <span className={"foot-note tone-" + notice.tone} role="status">{notice.text}</span>}
          {canBook && (
            <button className="btn" disabled={busy} onClick={() => act("book", {}, null, "Booking confirmed.")}>
              <Icon name="book" size={16} /> Book portal rate
            </button>
          )}
          {!m.delivery && (
            <button
              className="btn btn-primary"
              disabled={!canSend}
              onClick={() => act("quote", { revision: proposal.revision }, null, "Quote sent in the customer thread.")}
            >
              <Icon name="send" size={16} /> Send quote
            </button>
          )}
        </footer>
      )}
      {notice && !(request && proposal?.offer) && (
        <p className={"foot-note tone-" + notice.tone} role="status">{notice.text}</p>
      )}
    </>
  );
}
