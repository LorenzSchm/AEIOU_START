import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export function Kbd({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <kbd
      className={cn(
        "num inline-flex h-[18px] min-w-[18px] items-center justify-center rounded border border-line-strong bg-surface-3 px-1 text-[10px] font-medium text-muted",
        className,
      )}
    >
      {children}
    </kbd>
  );
}
