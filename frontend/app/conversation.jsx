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
  for (const message of messages) {
    if ((message.thread_id ?? message.root ?? message.id) !== threadId) continue;
    events.push({
      key: "in-" + message.id,
      id: message.id,
      root: message.id === threadId,
      label: inboxLabels[message.kind] || (message.stage.startsWith("bid ") ? "Carrier bid" : "Carrier email"),
      address: "From " + (message.sender || "unknown sender"),
      subject: message.subject,
      body: message.body,
      attachments: message.attachments,
      time: message.received,
      expanded: true,
    });
    for (const item of message.outgoing || []) {
      const recipient = item.recipient || (item.kind === "bid" ? "" : message.sender);
      events.push({
        key: item.action,
        id: item.message_id,
        label: outboxLabels[item.kind],
        address: "To " + (recipient || "unknown recipient"),
        state: item.state,
        subject: item.subject,
        body: item.body,
        time: item.time,
        expanded: item.kind !== "bid",
      });
    }
  }
  return events.sort((a, b) =>
    Number(Boolean(b.root)) - Number(Boolean(a.root)) ||
    (Date.parse(a.time) || Infinity) - (Date.parse(b.time) || Infinity) ||
    (a.id ?? Infinity) - (b.id ?? Infinity) || a.key.localeCompare(b.key),
  );
}

export default function Conversation({ messages, selected }) {
  return (
    <section className="conversation" aria-label="Conversation">
      <h3>Conversation</h3>
      <ol>
        {timeline(messages, selected).map((entry) => (
          <li key={entry.key}>
            <details open={entry.expanded}>
              <summary>
                <span className="thread-heading">
                  <span className="thread-label">{entry.label}</span>
                  {entry.state && <span className={"chip " + (entry.state === "sent" ? "ok" : "warn")}>
                    {deliveryLabels[entry.state] || entry.state}
                  </span>}
                  <span className="thread-address">{entry.address}</span>
                </span>
                {entry.time && <time dateTime={entry.time}>{timestamp.format(new Date(entry.time))}</time>}
              </summary>
              {entry.subject && <strong className="thread-subject">{entry.subject}</strong>}
              <pre className="mail-body">{entry.body ?? "The original message text was not recorded."}</pre>
              {entry.attachments?.map((file) => (
                <a className="attachment" key={file.id} download={file.filename}
                   href={`/api/v1/messages/${entry.id}/attachments/${file.id}`}>
                  {file.filename}
                </a>
              ))}
            </details>
          </li>
        ))}
      </ol>
    </section>
  );
}
