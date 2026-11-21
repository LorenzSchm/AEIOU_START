import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export function Panel({
  title,
  icon,
  actions,
  children,
  className,
  bodyClassName,
  count,
}: {
  title?: ReactNode;
  icon?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
  count?: number;
}) {
  return (
    <section className={cn("panel flex min-w-0 flex-col", className)}>
      {(title || actions) && (
        <header className="flex min-h-10 items-center gap-2 border-b border-line px-3.5 py-2">
          {icon && <span className="text-subtle [&>svg]:size-3.5">{icon}</span>}
          {title && <h2 className="text-[13px] font-semibold tracking-tight">{title}</h2>}
          {count != null && <span className="num text-xs text-subtle">{count}</span>}
          {actions && <div className="ml-auto flex items-center gap-1.5">{actions}</div>}
        </header>
      )}
      <div className={cn("min-h-0 flex-1", bodyClassName)}>{children}</div>
    </section>
  );
}

/** A label/value list for facts (RFQ, booking). */
export function Facts({ items, className }: { items: { label: string; value: ReactNode; mono?: boolean }[]; className?: string }) {
  return (
    <dl className={cn("grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-[13px]", className)}>
      {items.map((it) => (
        <div key={it.label} className="contents">
          <dt className="text-muted">{it.label}</dt>
          <dd className={cn("min-w-0 truncate text-right", it.mono && "num")}>{it.value ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}

export function PageBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("mx-auto w-full max-w-[1680px] px-3 py-4 sm:px-5 lg:px-6", className)}>{children}</div>;
}

export function PageTitle({
  title,
  subtitle,
  actions,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-end gap-x-4 gap-y-2">
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-0.5 text-[13px] text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="ml-auto flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
