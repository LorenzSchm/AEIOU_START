import { format, formatDistanceToNowStrict, isToday, parseISO } from "date-fns";

const CURRENCY_SYMBOL: Record<string, string> = { EUR: "€", USD: "$", GBP: "£" };

/** "1387.35" + "EUR" → "€1,387.35". Money arrives as decimal strings and is only formatted here. */
export function money(
  amount: string | null | undefined,
  currency?: string | null,
  opts: { compact?: boolean } = {},
): string {
  if (amount == null || amount === "") return "—";
  const n = Number(amount);
  if (!Number.isFinite(n)) return amount;
  const sym = currency ? (CURRENCY_SYMBOL[currency] ?? `${currency} `) : "";
  const digits = opts.compact && Math.abs(n) >= 1000 ? 0 : 2;
  return `${sym}${n.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

/** LLM cost: small numbers need more precision. */
export function usd(amount: string | number | null | undefined): string {
  if (amount == null || amount === "") return "—";
  const n = typeof amount === "number" ? amount : Number(amount);
  if (!Number.isFinite(n)) return String(amount);
  if (n === 0) return "$0";
  if (n < 0.01) return `$${n.toFixed(4)}`;
  return `$${n.toFixed(n < 1 ? 3 : 2)}`;
}

export function int(n: number | null | undefined): string {
  return n == null ? "—" : n.toLocaleString("en-US");
}

export function kg(value: string | number | null | undefined): string {
  if (value == null || value === "") return "—";
  return `${Number(value).toLocaleString("en-US", { maximumFractionDigits: 1 })} kg`;
}

/** 95 → "1:35", 3725 → "1:02:05". */
export function clock(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  return `${h > 0 ? `${h}:` : ""}${mm}:${String(sec).padStart(2, "0")}`;
}

export function seconds(n: number | null | undefined): string {
  if (n == null) return "—";
  if (n < 60) return `${Math.round(n)}s`;
  return clock(n);
}

export function ms(n: number | null | undefined): string {
  if (n == null) return "—";
  return n < 1000 ? `${n} ms` : `${(n / 1000).toFixed(n < 10_000 ? 2 : 1)} s`;
}

export function time(iso: string | null | undefined, withSeconds = true): string {
  if (!iso) return "—";
  return format(parseISO(iso), withSeconds ? "HH:mm:ss" : "HH:mm");
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = parseISO(iso);
  return isToday(d) ? format(d, "HH:mm") : format(d, "dd MMM HH:mm");
}

export function fullDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return format(parseISO(iso), "EEE dd MMM yyyy, HH:mm:ss");
}

export function shortDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  return format(parseISO(iso), "dd MMM");
}

export function ago(iso: string | null | undefined, now: number): string {
  if (!iso) return "—";
  if (now === 0) return "";
  const diff = (now - parseISO(iso).getTime()) / 1000;
  if (diff < 5) return "now";
  if (diff < 60) return `${Math.floor(diff)}s`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h`;
  return formatDistanceToNowStrict(parseISO(iso));
}

export function bytes(n: number | null | undefined): string {
  if (n == null) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

export function pct(n: number | null | undefined, digits = 0): string {
  return n == null ? "—" : `${(n * 100).toFixed(digits)}%`;
}

export function humanize(value: string): string {
  return value.replaceAll("_", " ").replaceAll(".", " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

/** "Katrin Vogel <k.vogel@x.de>" or "katrin.vogel@x.de" → "Katrin Vogel". */
export function addressName(addr: string | null | undefined): string {
  if (!addr) return "—";
  const m = addr.match(/^\s*"?([^"<]+?)"?\s*<.+>$/);
  if (m) return m[1];
  const local = addr.split("@")[0] ?? addr;
  return local
    .split(/[._-]+/)
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join(" ");
}
