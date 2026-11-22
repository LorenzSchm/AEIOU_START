// The desk's frame figures: inbox size, attention badge and list, globe routes. The server sends them
// in the summary (summary.desk, DeskSummary.desk); this local version serves mock mode and mirrors it.

import { needsAttention } from "./format";
import { globeState } from "./process";

export function localDesk(messages = [], now = Date.now()) {
  const attention = messages.filter((m) => needsAttention(m));
  return {
    messages: messages.length,
    attention: attention.length,
    attention_items: attention.slice(0, 4),
    routes: messages
      .filter((m) => m.kind === "request" && m.fields?.origin && m.fields?.destination)
      .map((m) => ({
        id: m.id,
        origin: m.fields.origin,
        destination: m.fields.destination,
        state: globeState(m, now),
        airline: m.proposal?.offer?.airline,
        totalEur: m.proposal?.offer?.total_eur,
        days: m.proposal?.offer?.days,
        label: m.subject,
      })),
  };
}
