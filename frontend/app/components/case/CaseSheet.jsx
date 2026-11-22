"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import Conversation from "../../conversation";
import EmailPreview from "../../email-preview";
import Icon from "../ui/Icon";
import { Chip, CountdownRing, RouteBadge, Section } from "../ui/primitives";
import OfferBars from "./OfferBars";
import BoardScore from "./BoardScore";
import Select from "../ui/Select";
import ConfirmDialog from "../ui/ConfirmDialog";
import { inWindow, useNow } from "../../lib/clock";
import Timeline from "./Timeline";
import { deriveProcess } from "../../lib/process";
import { api } from "../../lib/api";
import { answered, clock, eur, kindLabel, needsAttention, secondsLeft, shipmentBookings, statusOf, transitLabel } from "../../lib/format";
import { spring } from "../../lib/motion";

const RATE_PORTAL = "https://start-hack.5u.ai/portal/ui";
const EMPTY_PORTAL_RATE = { airline: "", rate_id: "", air_total_eur: "", transit_days: "", valid_until: "" };

/** "€2,315.00", "2.315,00" or "2315" → "2315.00": the last separator followed by 1–2 digits is the decimal point. */
function eurAmount(text) {
  const raw = text.replace(/[^0-9.,]/g, "");
  const m = raw.match(/^(.*?)[.,](\d{1,2})$/);
  return m ? m[1].replace(/[.,]/g, "") + "." + m[2] : raw.replace(/[.,]/g, "");
}

/** "HH:MM" on the operator's clock → ISO instant today; blank lets the backend apply its default. */
function validUntilIso(time) {
  if (!time) return null;
  const [h, min] = time.split(":").map(Number);
  const at = new Date();
  at.setHours(h, min, 0, 0);
  return at.toISOString();
}

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

function CaseContent({ m, messages, directory, refresh, onClose, intro, onDirtyChange, showBoard }) {
  // The open case ticks only while its quote window is running.
  const now = useNow(inWindow(m));
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
  const [portalDialog, setPortalDialog] = useState(false);
  const [portalRate, setPortalRate] = useState(EMPTY_PORTAL_RATE);
  const portalPrompted = useRef(false);
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
  const done = Boolean(m.delivery) || answered(m);
  const closed = done || (remaining !== null && remaining <= 0);
  const request = m.kind === "request";
  const portalUnavailable = m.review_reason?.startsWith("Portal rates unavailable.");
  const canOpenPortal = request && !closed && fields.origin && fields.destination && fields.weight_kg
    && fields.volume_cbm != null;
  const dismissPortal = useCallback(() => setPortalDialog(false), []);
  useEffect(() => {
    if (canOpenPortal && portalUnavailable && !portalPrompted.current) {
      portalPrompted.current = true;
      setPortalDialog(true);
    }
  }, [canOpenPortal, portalUnavailable]);
  function openPortal() {
    window.open(RATE_PORTAL, "_blank", "popup,width=760,height=820,noopener,noreferrer");
    setPortalDialog(false);
  }
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
  const attention = needsAttention(m, now) || (Boolean(m.review_reason) && !closed);
  const shipments = shipmentBookings(m);
  const canBook = m.delivery === "sent" && proposal?.offer?.rate_id && !m.booking && !shipments.booked;

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
          {request && m.received && <CountdownRing received={m.received} size={52} done={done} />}
          <Chip tone={status.tone}>{status.label}</Chip>
        </div>
        <button ref={closeRef} className="icon-btn" aria-label="Close case" onClick={onClose}>
          <Icon name="close" />
        </button>
      </header>

      {answered(m) && (
        <div className="banner banner-ok" role="status">
          <Icon name="check" size={16} />
          <span>Answered. No action needed.{m.review_reason ? ` ${m.review_reason}` : ""}</span>
        </div>
      )}
      {m.review_reason && !answered(m) && (
        <div className={"banner " + (closed ? "banner-muted" : "banner-warn")} role="status">
          <Icon name="alert" size={16} />
          <span>{closed ? `Closed. No action possible any more. ${m.review_reason}` : m.review_reason}</span>
          {canOpenPortal && portalUnavailable && (
            <button className="btn" onClick={() => setPortalDialog(true)}>Open rate lookup</button>
          )}
        </div>
      )}

      <div className="sheet-body">
        {/* The 5U verdict is challenge evaluation: shown only when the case is opened from the Scoreboard. */}
        {showBoard && <BoardScore result={m.board_result} />}
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

        {request && shipments.total > 0 && (
          <Section
            title="Shipment bookings"
            icon="book"
            defaultOpen
            badge={shipments.booked + "/" + shipments.total}
            summary={shipments.booked === shipments.total ? "All booked" : shipments.booked ? "Partly booked" : "Not booked"}
          >
            <ul className="bid-list">
              {shipments.legs.map((l) => (
                <li key={l.number}>
                  <Icon name="plane" size={14} />
                  <span>
                    {l.number}. {l.origin || "?"} → {l.destination || "?"}
                    {l.booking_reference && <> · <b className="mono">{l.booking_reference}</b> · {l.airline || "—"}
                      {l.price != null && <> · {eur(l.price, 2)}</>}</>}
                  </span>
                  <Chip tone={l.booking_reference ? "ok" : "muted"}>{l.booking_reference ? l.status || "booked" : "not booked"}</Chip>
                </li>
              ))}
            </ul>
          </Section>
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
              {canOpenPortal && !portalUnavailable && (
                <div className="actions">
                  <button className="btn" onClick={() => setPortalDialog(true)}>Look up a portal rate</button>
                </div>
              )}
              {canOpenPortal && (
                <>
                  <div className="grid">
                    {[["airline", "Portal rate · airline"], ["rate_id", "Rate ID · R-…"], ["air_total_eur", "Air total · EUR"],
                      ["transit_days", "Transit · days"], ["valid_until", "Valid until", "time"]].map(([k, label, type]) => (
                      <label className="field" key={k}>
                        <span>{label}</span>
                        <input type={type || "text"} value={portalRate[k]}
                          onChange={(e) => { setDirty(true); setPortalRate({ ...portalRate, [k]: e.target.value }); }} />
                      </label>
                    ))}
                  </div>
                  <div className="actions">
                    <button
                      className="btn"
                      disabled={!portalRate.airline.trim() || !portalRate.rate_id.trim() || !portalRate.air_total_eur || !portalRate.transit_days}
                      onClick={() => act("portal-rate", {
                        airline: portalRate.airline.trim(),
                        rate_id: portalRate.rate_id.trim(),
                        air_total_eur: eurAmount(portalRate.air_total_eur),
                        transit_days: parseFloat(portalRate.transit_days.replace(",", ".")) || null,
                        valid_until: validUntilIso(portalRate.valid_until),
                      }, () => setPortalRate(EMPTY_PORTAL_RATE), "Portal rate added. The worker will compare and quote it.")}
                    >
                      Add portal rate
                    </button>
                  </div>
                </>
              )}
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
      <ConfirmDialog open={portalDialog && !closed} title="Look up the 5U rate"
        message={<>
          {fields.origin} → {fields.destination} · {fields.weight_kg} kg · {fields.volume_cbm} m³
          <br />
          Look up the rate on 5U’s page, then enter the airline, rate ID, EUR air total, transit and validity under Carrier bids. The worker quotes it like an API rate.
        </>}
        confirmLabel="Open 5U portal" confirmStyle="primary" onConfirm={openPortal} onCancel={dismissPortal} />
    </>
  );
}
