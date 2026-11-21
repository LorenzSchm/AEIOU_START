"use client";

import { Check, Copy } from "lucide-react";
import { useState, type ReactNode } from "react";
import { cn } from "@/lib/cn";

/** Small syntax-highlighted JSON renderer, no dependencies. */
function render(value: unknown, indent: number): ReactNode {
  const pad = "  ".repeat(indent);
  const padIn = "  ".repeat(indent + 1);
  if (value === null) return <span className="text-pink">null</span>;
  if (typeof value === "boolean") return <span className="text-pink">{String(value)}</span>;
  if (typeof value === "number") return <span className="text-cyan">{value}</span>;
  if (typeof value === "string") return <span className="text-ok">{JSON.stringify(value)}</span>;
  if (Array.isArray(value)) {
    if (value.length === 0) return "[]";
    return (
      <>
        {"[\n"}
        {value.map((v, i) => (
          <span key={i}>
            {padIn}
            {render(v, indent + 1)}
            {i < value.length - 1 ? ",\n" : "\n"}
          </span>
        ))}
        {pad}]
      </>
    );
  }
  if (typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return "{}";
    return (
      <>
        {"{\n"}
        {entries.map(([k, v], i) => (
          <span key={k}>
            {padIn}
            <span className="text-accent">{JSON.stringify(k)}</span>: {render(v, indent + 1)}
            {i < entries.length - 1 ? ",\n" : "\n"}
          </span>
        ))}
        {pad}
        {"}"}
      </>
    );
  }
  return String(value);
}

export function JsonView({ value, className, maxHeight = "max-h-96" }: { value: unknown; className?: string; maxHeight?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className={cn("group relative rounded-lg border border-line bg-bg", className)}>
      <button
        onClick={() => {
          void navigator.clipboard?.writeText(JSON.stringify(value, null, 2));
          setCopied(true);
          setTimeout(() => setCopied(false), 1200);
        }}
        className="absolute top-2 right-2 rounded-md border border-line bg-surface-2 p-1 text-subtle opacity-0 transition-opacity group-hover:opacity-100 hover:text-fg"
        aria-label="Copy JSON"
      >
        {copied ? <Check className="size-3.5 text-ok" /> : <Copy className="size-3.5" />}
      </button>
      <pre className={cn("num overflow-auto p-3 text-xs leading-relaxed whitespace-pre text-muted", maxHeight)}>
        {render(value, 0)}
      </pre>
    </div>
  );
}
