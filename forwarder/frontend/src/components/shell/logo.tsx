/** Wordmark: a stylised flight path over a dot, monospace name. */
export function Logo({ collapsed }: { collapsed?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <svg viewBox="0 0 28 28" className="size-7 shrink-0" aria-hidden>
        <rect width="28" height="28" rx="7" className="fill-accent/12 stroke-accent/30" strokeWidth="1" />
        <path d="M6 19.5C10 10 18 8 22.5 8.5" fill="none" className="stroke-accent" strokeWidth="1.6" strokeLinecap="round" strokeDasharray="2.2 2.4" />
        <circle cx="6" cy="19.5" r="2" className="fill-attn" />
        <path d="M20 6.2l3.6 2.2-3.2 2.6" fill="none" className="stroke-accent" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {!collapsed && (
        <div className="leading-none">
          <div className="num text-[15px] font-semibold tracking-tight">forwarder</div>
          <div className="mt-1 text-[10px] font-medium tracking-[0.14em] text-subtle uppercase">AEIOU Air Freight</div>
        </div>
      )}
    </div>
  );
}
