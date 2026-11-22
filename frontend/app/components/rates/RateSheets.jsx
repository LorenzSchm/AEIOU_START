import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import ConfirmDialog from "../ui/ConfirmDialog";
import Icon from "../ui/Icon";

const receivedDate = new Intl.DateTimeFormat("en-GB", {
  dateStyle: "medium", timeStyle: "short", timeZone: "Europe/Berlin",
});

function status(sheet, today) {
  switch (sheet.state) {
    case "active": return sheet.valid_to < today ? "Expired" : "Preferred";
    case "approved": return sheet.valid_from > today ? "Scheduled" : sheet.valid_to < today ? "Expired" : "Approved";
    case "paused": return sheet.valid_to < today ? "Expired" : "Paused";
    case "pending": return "Awaiting approval";
    case "blocked": return "Blocked";
    case "rejected": return "Rejected";
    default: return "Reading attachment";
  }
}

const actions = {
  approve: {
    label: "Approve rates", title: "Approve this rate sheet?",
    message: (sheet) => sheet.carrier + " prices can be used from " + sheet.valid_from + " through " + sheet.valid_to + ".",
  },
  reject: {
    label: "Reject sheet", title: "Reject this rate sheet?", style: "destructive",
    message: (sheet) => sheet.filename + " will be marked rejected.",
  },
  activate: {
    label: "Use now", title: "Use this rate sheet now?",
    message: (sheet) => "New quotes will prefer this sheet for the " + sheet.carrier + " lanes it covers.",
  },
  pause: {
    label: "Pause sheet", title: "Pause this rate sheet?",
    message: (sheet) => "New quotes will use other eligible " + sheet.carrier + " rates where available.",
  },
  resume: {
    label: "Restore schedule", title: "Restore this rate sheet’s schedule?",
    message: (sheet) => "This sheet will become eligible on " + sheet.valid_from + ".",
  },
};

function decisions(sheet, today) {
  if (sheet.state === "pending") return ["reject", "approve"];
  if (["blocked", "queued"].includes(sheet.state)) return ["reject"];
  if (!sheet.valid_to || sheet.valid_to < today) return [];
  if (sheet.state === "active") return ["pause"];
  if (sheet.state === "approved") return sheet.valid_from <= today ? ["pause", "activate"] : ["pause"];
  if (sheet.state === "paused") return [sheet.valid_from <= today ? "activate" : "resume"];
  return [];
}

function RateTable({ rates }) {
  const breaks = [...new Set(rates.flatMap((rate) => Object.keys(rate.breaks)))].sort((a, b) => a - b);
  return (
    <div className="rate-table" tabIndex={0} role="region" aria-label="Lane prices">
      <table>
        <caption>EUR per kg except minimum charge and transit</caption>
        <thead><tr>
          <th scope="col">Lane</th><th scope="col">Days</th><th scope="col">Minimum</th>
          {breaks.map((weight) => <th scope="col" key={weight}>≥ {weight} kg</th>)}
          <th scope="col">Fuel</th><th scope="col">Security</th>
        </tr></thead>
        <tbody>{rates.map((rate) => (
          <tr key={rate.origin + rate.destination}>
            <th scope="row">{rate.origin} → {rate.destination}</th><td>{rate.days}</td><td>{rate.minimum}</td>
            {breaks.map((weight) => <td key={weight}>{rate.breaks[weight] ?? "—"}</td>)}
            <td>{rate.fuel}</td><td>{rate.security}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

export default function RateSheets() {
  const [queue, setQueue] = useState(null);
  const [pollError, setPollError] = useState("");
  const [actionError, setActionError] = useState("");
  const [action, setAction] = useState(null);
  const [saving, setSaving] = useState(false);
  const latestFetch = useRef(0);

  const refresh = useCallback(async () => {
    const request = ++latestFetch.current;
    try {
      const next = await api("/tariff-sheets");
      if (request === latestFetch.current) {
        setQueue(next);
        setPollError("");
      }
    } catch (error) {
      if (request === latestFetch.current) setPollError(error.message);
    }
  }, []);

  useEffect(() => {
    let active = true;
    let timer;
    async function poll() {
      await refresh();
      if (active) timer = setTimeout(poll, 5000);
    }
    poll();
    return () => {
      active = false;
      latestFetch.current++;
      clearTimeout(timer);
    };
  }, [refresh]);

  const cancel = useCallback(() => setAction(null), []);
  async function confirm() {
    const { sheet, decision } = action;
    setAction(null);
    setSaving(true);
    setActionError("");
    try {
      await api("/tariff-sheets/" + sheet.id + "/decision", { decision });
    } catch (error) {
      setActionError(error.message);
    } finally {
      await refresh();
      setSaving(false);
    }
  }
  const error = actionError || pollError;
  const selected = action && actions[action.decision];

  return (
    <div className="rate-sheets">
      {error && <p className="banner banner-bad" role="alert">{error}</p>}
      {queue && !queue.sheets.length && <p className="rate-sheet glass">No carrier rate sheets have been detected.</p>}
      {!queue && !error && <p role="status">Loading rate sheets…</p>}
      {queue?.sheets.map((sheet) => (
        <article className="rate-sheet glass" key={sheet.id}>
          <div className="rate-sheet-head">
            <div>
              <h2>{sheet.carrier}</h2>
              <a href={`/api/v1/messages/${sheet.message_id}/attachments/${sheet.attachment_id}`} download={sheet.filename}>
                <Icon name="paperclip" size={15} />{sheet.filename}
              </a>
            </div>
            <span className="rate-sheet-status">{status(sheet, queue.today)}</span>
          </div>
          {sheet.received && <p className="rate-sheet-received">
            Received <time dateTime={sheet.received}>{receivedDate.format(new Date(sheet.received))}</time> · Berlin
          </p>}
          {sheet.valid_from && <p>Valid {sheet.valid_from} through {sheet.valid_to} · Berlin</p>}
          {sheet.issue && <p className="rate-sheet-issue">{sheet.issue}</p>}
          {sheet.rates.length > 0 && (
            <details>
              <summary>Review {sheet.rates.length} {sheet.rates.length === 1 ? "lane" : "lanes"}</summary>
              <RateTable rates={sheet.rates} />
            </details>
          )}
          {queue.can_decide && decisions(sheet, queue.today).length > 0 && (
            <div className="rate-sheet-actions">
              {decisions(sheet, queue.today).map((decision) => (
                <button key={decision} className={["approve", "activate"].includes(decision) ? "btn btn-primary" : "btn"}
                  disabled={saving} onClick={() => setAction({ sheet, decision })}>
                  {actions[decision].label}
                </button>
              ))}
            </div>
          )}
        </article>
      ))}
      <ConfirmDialog open={Boolean(action)}
        title={selected?.title}
        message={selected?.message(action.sheet)}
        confirmLabel={selected?.label}
        confirmStyle={selected?.style ?? "primary"}
        onConfirm={confirm} onCancel={cancel} />
    </div>
  );
}
