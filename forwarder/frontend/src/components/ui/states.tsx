import { CircleAlert, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { ApiError } from "@/api/fetcher";
import { cn } from "@/lib/cn";

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cn("skeleton h-4", className)} />;
}

export function SkeletonRows({ rows = 6, className }: { rows?: number; className?: string }) {
  return (
    <div className={cn("flex flex-col gap-px", className)} aria-busy>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-3">
          <Skeleton className="h-5 w-20" />
          <Skeleton className="h-4 flex-1" />
          <Skeleton className="h-4 w-16" />
        </div>
      ))}
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  hint,
  action,
  className,
}: {
  icon: LucideIcon;
  title: string;
  hint?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center justify-center gap-2 px-6 py-14 text-center", className)}>
      <div className="mb-1 grid size-10 place-items-center rounded-full border border-line bg-surface-2">
        <Icon className="size-4.5 text-subtle" />
      </div>
      <p className="text-sm font-medium">{title}</p>
      {hint && <p className="max-w-sm text-xs text-muted">{hint}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, className }: { error: unknown; className?: string }) {
  const status = error instanceof ApiError ? error.status : null;
  const message = error instanceof Error ? error.message : "Something went wrong.";
  return (
    <div className={cn("flex flex-col items-center gap-2 px-6 py-12 text-center", className)} role="alert">
      <CircleAlert className="size-5 text-danger" />
      <p className="text-sm font-medium">
        {status === 502 ? "Backend is not reachable" : "Could not load this"}
      </p>
      <p className="max-w-md text-xs text-muted">
        {message}
        {status ? <span className="num ml-1 text-subtle">({status})</span> : null}
      </p>
      <p className="text-2xs text-subtle">It will reload by itself once the live stream reconnects.</p>
    </div>
  );
}
