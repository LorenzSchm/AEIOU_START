"use client";

import { useEffect, useState } from "react";
import Conversation from "./conversation";
import EmailPreview from "./email-preview";

function transitLabel(days) {
  const hours = Math.round(days * 24);
  const whole = Math.floor(hours / 24);
  const rest = hours % 24;
  const parts = [];
  if (whole || !rest) parts.push(whole + (whole === 1 ? " day" : " days"));
  if (rest) parts.push(rest + (rest === 1 ? " hour" : " hours"));
  return parts.join(" ");
}
const base = "/api/v1";
const stamp = (value) =>
  value ? new Date(value).toLocaleTimeString([], { hour12: false }) : "—";
const secondsLeft = (m) =>
  m.received
    ? Math.ceil((Date.parse(m.received) + 360000 - Date.now()) / 1000)
    : null;
const state = (m) =>
  m.delivery ||
  (m.kind === "request" && secondsLeft(m) !== null && secondsLeft(m) <= 0
    ? "expired"
    : m.stage);
const tone = (s) =>
  ["sent", "booked", "quoted"].includes(s)
    ? "ok"
    : ["expired", "review", "operator review", "uncertain", "pending"].includes(s)
      ? "warn"
      : "";
const countdown = (n) =>
  n === null
    ? "Time unknown"
    : n <= 0
      ? "Window closed"
      : Math.floor(n / 60) + "m " + (n % 60) + "s left";

async function api(path, data) {
  const r = await fetch(
    base + path,
    data === undefined
      ? { cache: "no-store" }
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(data),
        },
  );
  if (!r.ok) {
    let detail;
    try {
      detail = (await r.json()).detail;
    } catch {}
    throw Error(
      Array.isArray(detail)
        ? detail.map((e) => e.loc.slice(1).join(".") + " — " + e.msg).join("\n")
        : detail || "Service returned HTTP " + r.status,
    );
  }
  return r.json();
}

function Case({ m, messages, directory, refresh }) {
  const [fields, setFields] = useState(m.fields);
  const [dirty, setDirty] = useState(false);
  const [bid, setBid] = useState({
    airline: "",
    air_total_eur: "",
    transit_days: "",
  });
  const [recipient, setRecipient] = useState("");
  const [reply, setReply] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const remaining = secondsLeft(m);
  const closed = Boolean(m.delivery) || (remaining !== null && remaining <= 0);
  const request = m.kind === "request";
  const proposal = m.proposal;
  const suitable = directory.carriers.filter((c) =>
    c.serves
      .split(" ")
      .includes(c.kind === "trucker" ? fields.pickup_zone : fields.destination),
  );
  const set = (key, value) => {
    setDirty(true);
    setFields((old) => {
      const updated = { ...old, [key]: value };
      if (key === "pickup_zone" && directory.zones[value])
        updated.origin = directory.zones[value];
      return updated;
    });
  };
  function shipment() {
    return {
      ...fields,
      pickup_zone: fields.pickup_zone || null,
      pickup_eur: fields.pickup_eur === "" ? null : fields.pickup_eur,
      pickup_days: Number(fields.pickup_days || 0),
    };
  }
  async function act(endpoint, payload, done, success) {
    setNotice("");
    setBusy(true);
    try {
      await api("/messages/" + m.id + "/" + endpoint, payload);
      if (done) done();
      setNotice(success);
      await refresh();
    } catch (e) {
      setNotice(e.message);
    } finally {
      setBusy(false);
    }
  }
  function input(key, title, type = "text", maxLength) {
    return (
      <label>
        {title}
        <input
          type={type}
          step={type === "number" ? "any" : undefined}
          min={type === "number" ? "0" : undefined}
          value={fields[key] ?? ""}
          maxLength={maxLength}
          onChange={(e) =>
            set(
              key,
              type === "text" ? e.target.value.toUpperCase() : e.target.value,
            )
          }
        />
      </label>
    );
  }
  return (
    <article>
      <header className="case-head">
        <div className="meta">
          {m.kind} · #{m.id} · {stamp(m.received)}
        </div>
        <h2>{m.subject || "(No subject)"}</h2>
        <div className="sender">{m.sender || "Unknown sender"}</div>
        {request && (
          <strong className={"timer " + (closed ? "closed" : "")}>
            {m.delivery || countdown(remaining)}
          </strong>
        )}
      </header>
      {m.review_reason && (
        <p className="notice" role="status">
          {m.review_reason}
        </p>
      )}
      {m.board_result ? (
        <p className="note">
          5U board · Round {m.board_result.round_no} · {m.board_result.verdict}
          {m.board_result.reason ? ` · ${m.board_result.reason.replaceAll("_", " ")}` : ""}
          {m.board_result.total != null ? ` · ${m.board_result.total} points` : ""}
          {Object.entries(m.board_result.points ?? {}).map(([kind, points]) => (
            <span key={kind}> · {kind} {points}</span>
          ))}
          {m.board_result.cost_pending ? " · cost points pending" : ""}
        </p>
      ) : null}
      <Conversation messages={messages} selected={m} />
      {request && (
        <>
          {proposal?.pickup_eur != null && (
            <p className="pickup">
              Pickup · EUR {proposal.pickup_eur} ·{" "}
              {proposal.trucker || "Operator rate"}
            </p>
          )}
          <section>
            <h3>Shipment</h3>
            <fieldset disabled={busy || closed}>
              <div className="grid">
                {input("origin", "Origin airport", "text", 3)}
                {input("destination", "Destination airport", "text", 3)}
                {input("weight_kg", "Gross weight · kg", "number")}
                {input("volume_cbm", "Volume · m³", "number")}
                <label>
                  Customer priority
                  <select
                    value={fields.preference || ""}
                    onChange={(e) => set("preference", e.target.value)}
                  >
                    <option value="">Choose priority</option>
                    <option value="cheapest">Cheapest</option>
                    <option value="fastest">Fastest</option>
                  </select>
                </label>
                {input("pickup_zone", "Pickup zone (if required)", "text", 2)}
                {fields.pickup_zone && (
                  <>
                    {input("pickup_eur", "Truck total · EUR", "number")}
                    {input("pickup_days", "Truck transit · days", "number")}
                  </>
                )}
              </div>
              <details>
                <summary>Carrier bids</summary>
                <div className="bid-row">
                  <label>
                    Carrier serving this shipment
                    <select
                      value={recipient}
                      onChange={(e) => setRecipient(e.target.value)}
                    >
                      <option value="">Choose carrier</option>
                      {suitable.map((c) => (
                        <option value={c.email} key={c.email}>
                          {c.name} · {c.kind}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button
                    disabled={!recipient || Boolean(m.bid_requests[recipient])}
                    onClick={() =>
                      act(
                        "bids",
                        { shipment: shipment(), email: recipient },
                        null,
                        "Bid request sent.",
                      )
                    }
                  >
                    {m.bid_requests[recipient] || "Request bid"}
                  </button>
                </div>
                <div className="grid">
                  {[
                    ["airline", "Quoted airline"],
                    ["air_total_eur", "Air total · EUR"],
                    ["transit_days", "Air transit · days"],
                  ].map(([k, label]) => (
                    <label key={k}>
                      {label}
                      <input
                        value={bid[k]}
                        onChange={(e) => {
                          setDirty(true);
                          setBid({ ...bid, [k]: e.target.value });
                        }}
                      />
                    </label>
                  ))}
                </div>
              </details>
              {m.review_reason && (
                <button
                  onClick={() =>
                    act(
                      "resume",
                      shipment(),
                      () => setDirty(false),
                      "Returned to automatic pricing.",
                    )
                  }
                >
                  Resume with these fields
                </button>
              )}
              <button
                className="primary"
                onClick={() =>
                  act(
                    "price",
                    {
                      shipment: shipment(),
                      carrier_bid: Object.values(bid).some(Boolean)
                        ? bid
                        : null,
                    },
                    () => setDirty(false),
                    "Pricing updated.",
                  )
                }
              >
                {busy ? "Working…" : "Compare rates"}
              </button>
            </fieldset>
          </section>
          {proposal && (
            <section>
              <h3>Quote</h3>
              {proposal.notes.map((n) => (
                <p key={n} className="note">
                  {n}
                </p>
              ))}
              {proposal.offer && (
                <>
                  <div className="amount">
                    <small>EUR</small> {proposal.offer.total_eur}
                  </div>
                  <p className="offer-meta">
                    {proposal.offer.airline} · {transitLabel(proposal.offer.days)} ·{" "}
                    {proposal.offer.source}
                  </p>
                  <details>
                    <summary>
                      Compared offers ({proposal.alternatives.length})
                    </summary>
                    <table>
                      <tbody>
                        {proposal.alternatives.map((o, i) => (
                          <tr key={i}>
                            <td>{o.airline}</td>
                            <td>{o.source}</td>
                            <td>{transitLabel(o.days)}</td>
                            <td>EUR {o.total_eur}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </details>
                  {!m.delivery && <EmailPreview body={proposal.body} fields={fields} />}
                  <button
                    className="primary"
                    disabled={busy || closed || dirty}
                    onClick={() =>
                      act(
                        "quote",
                        { revision: proposal.revision },
                        null,
                        "Quote sent in the customer thread.",
                      )
                    }
                  >
                    Send first quote
                  </button>
                  {dirty && !closed && (
                    <p className="hint">Compare rates again to apply your edits.</p>
                  )}
                  {m.delivery === "sent" &&
                    proposal.offer.rate_id &&
                    !m.booking && (
                      <button
                        disabled={busy}
                        onClick={() =>
                          act("book", {}, null, "Booking confirmed.")
                        }
                      >
                        Book accepted portal quote
                      </button>
                    )}
                  {m.booking && <p className="booked">Booked · {m.booking.booking_reference}</p>}
                </>
              )}
            </section>
          )}
          {["uncertain", "pending"].includes(m.delivery) && (
            <p className="note">
              Delivery unconfirmed. Check the team mailbox before resending
              manually.
            </p>
          )}
        </>
      )}
      {!request && (
        <section>
          <h3>Thread reply</h3>
          <textarea
            rows={5}
            value={reply}
            onChange={(e) => setReply(e.target.value)}
            aria-label="Reply body"
          />
          {reply.trim() && <EmailPreview body={reply} fields={fields} />}
          <button
            className="primary"
            disabled={busy || !reply.trim() || Boolean(m.reply_delivery)}
            onClick={() =>
              act("reply", { body: reply }, () => setReply(""), "Reply sent.")
            }
          >
            {m.reply_delivery || "Send reply"}
          </button>
        </section>
      )}
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
    </article>
  );
}

export default function Page() {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [selected, select] = useState(null);
  const [filter, setFilter] = useState("");
  const [exceptionsOnly, setExceptionsOnly] = useState(true);
  async function refresh() {
    const [status, messages, directory] = await Promise.all([
      api("/status"),
      api("/messages"),
      api("/directory"),
    ]);
    setData({ status, messages, directory });
    setError("");
  }
  useEffect(() => {
    let active = true,
      timer;
    async function tick() {
      try {
        if (active) await refresh();
      } catch (e) {
        if (active) setError(e.message);
      }
      if (active) timer = setTimeout(tick, 2000);
    }
    tick();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, []);
  const exceptions = (data?.messages || []).filter(
    (m) =>
      m.kind === "request" &&
      ([
        "review",
        "waiting for pickup",
        "waiting for air price",
        "operator review",
      ].includes(m.stage) ||
        ["uncertain", "pending"].includes(m.delivery)) &&
      m.delivery !== "sent",
  );
  const list = (exceptionsOnly ? exceptions : data?.messages || []).filter(
    (m) =>
      (m.subject + " " + m.sender + " " + m.id + " " + m.body)
        .toLowerCase()
        .includes(filter.toLowerCase()),
  );
  const current = list.find((m) => m.id === selected) || list[0];
  return (
    <>
      <header className="top">
        <strong>
          <i aria-hidden="true" />
          AEIOU <span>/</span> Dispatch
        </strong>
        <span>Air export operations</span>
      </header>
      <main>
        <div className="heading">
          <div>
            <div className="eyebrow">Operations · Live</div>
            <h1>Wave desk</h1>
          </div>
          <span
            className={"signal " + (error || data?.status.error ? "bad" : "")}
          >
            {error || data?.status.error
              ? "Connection issue"
              : data?.status.last_poll
                ? "Mailbox connected"
                : "Connecting…"}
          </span>
        </div>
        <div className="metrics">
          <div>
            <small>Inbox</small>
            <b>{data?.messages.length ?? "—"}</b>
          </div>
          <div>
            <small>Kit rates</small>
            <b>{data?.status.tariffs ?? "—"}</b>
          </div>
          <div>
            <small>Last poll</small>
            <b>{stamp(data?.status.last_poll)}</b>
          </div>
          <div>
            <small>Quote mode</small>
            <b>
              {data
                ? data.status.auto_send
                  ? data.status.start_at &&
                    Date.parse(data.status.start_at) > Date.now()
                    ? "Armed · " +
                      new Date(data.status.start_at).toLocaleTimeString([], {
                        timeZone: "Europe/Berlin",
                        hour12: false,
                        hour: "2-digit",
                        minute: "2-digit",
                      }) +
                      " Berlin"
                    : "Automatic"
                  : "Operator review"
                : "—"}
            </b>
          </div>
        </div>
        {(error || data?.status.error) && (
          <p className="notice" role="alert">
            {error || data.status.error}
          </p>
        )}
        <div className="workspace">
          <aside>
            <h2>{exceptionsOnly ? "Needs attention" : "Mailbox"}</h2>
            <div className="queue-tabs">
              <button
                aria-pressed={exceptionsOnly}
                onClick={() => setExceptionsOnly(true)}
              >
                Exceptions ({exceptions.length})
              </button>
              <button
                aria-pressed={!exceptionsOnly}
                onClick={() => setExceptionsOnly(false)}
              >
                All mail
              </button>
            </div>
            <input
              type="search"
              placeholder="Search messages"
              aria-label="Search messages"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <nav>
              {list.map((m) => (
                <button
                  key={m.id}
                  aria-current={current?.id === m.id}
                  onClick={() => select(m.id)}
                >
                  <span className="meta">
                    {m.kind} · {stamp(m.received)}
                  </span>
                  <strong>{m.subject || "(No subject)"}</strong>
                  <span className="row-foot">
                    <em className={"chip " + tone(state(m))}>{state(m)}</em>
                    #{m.id}
                  </span>
                </button>
              ))}
            </nav>
            {!list.length && (
              <p>
                {filter
                  ? "No matches."
                  : exceptionsOnly
                    ? "No flagged requests."
                    : "Inbox empty."}
              </p>
            )}
          </aside>
          {current ? (
            <Case
              key={current.id}
              m={current}
              messages={data.messages}
              directory={data.directory}
              refresh={refresh}
            />
          ) : (
            <article className="empty">
              <h2>
                {filter
                  ? "No matching messages"
                  : exceptionsOnly
                    ? "No requests need attention"
                    : "Waiting for incoming mail"}
              </h2>
              <p>
                {filter
                  ? "Change the search to find a thread."
                  : exceptionsOnly
                    ? "The worker handles bids, pickup and quotes automatically. Open All mail to see its activity."
                    : "Requests and carrier replies appear here as they arrive."}
              </p>
            </article>
          )}
        </div>
      </main>
    </>
  );
}
