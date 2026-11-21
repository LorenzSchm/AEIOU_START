"use client";

import { Keyboard, LogOut, Menu, X } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useGetStats } from "@/api/generated/stats/stats";
import { logout } from "@/app/login/actions";
import { Kbd } from "@/components/ui/kbd";
import { Modal } from "@/components/ui/drawer";
import { cn } from "@/lib/cn";
import { MOCK_MODE } from "@/lib/mock-mode";
import { focusSearch, useGoSequence, useKeys } from "@/lib/use-hotkeys";
import { useNow } from "@/lib/use-now";
import { AutopilotIndicator } from "./autopilot-indicator";
import { ConnectionIndicator } from "./connection-indicator";
import { Logo } from "./logo";
import { NAV, isActive } from "./nav";
import { ThemeToggle } from "./theme-toggle";

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  const { data: stats } = useGetStats({ hours: 24 });
  const needsHuman = stats?.needsHuman ?? 0;

  return (
    <nav className="flex flex-col gap-0.5" aria-label="Main">
      {NAV.map((item) => {
        const active = isActive(pathname, item.href);
        const Icon = item.icon;
        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onNavigate}
            aria-current={active ? "page" : undefined}
            className={cn(
              "group relative flex h-8 items-center gap-2.5 rounded-lg px-2.5 text-[13px] font-medium transition-colors",
              active ? "bg-surface-3 text-fg" : "text-muted hover:bg-surface-2 hover:text-fg",
            )}
          >
            {active && <span className="absolute top-1.5 bottom-1.5 -left-3 w-0.5 rounded-full bg-accent" />}
            <Icon className={cn("size-4", active ? "text-accent" : "text-subtle group-hover:text-muted")} />
            <span className="flex-1">{item.label}</span>
            {item.href === "/" && needsHuman > 0 ? (
              <span className="num rounded-full bg-attn px-1.5 text-[10px] leading-4 font-semibold text-[#1b1200]" title="Sessions that need a human">
                {needsHuman}
              </span>
            ) : (
              <Kbd className="opacity-0 transition-opacity group-hover:opacity-100">g {item.key}</Kbd>
            )}
          </Link>
        );
      })}
    </nav>
  );
}

function SidebarFooter({ onShortcuts }: { onShortcuts: () => void }) {
  return (
    <div className="mt-auto flex flex-col gap-3 border-t border-line pt-3">
      {MOCK_MODE && (
        <div className="rounded-lg border border-violet/25 bg-violet/8 px-2.5 py-2 text-2xs text-violet">
          <span className="font-semibold">Demo data.</span> Mock mode with simulated mail traffic, no backend.
        </div>
      )}
      <div className="flex items-center justify-between gap-2">
        <ThemeToggle />
        <div className="flex items-center gap-1">
          <button
            onClick={onShortcuts}
            className="grid size-7 place-items-center rounded-md text-subtle hover:bg-surface-3 hover:text-fg"
            aria-label="Keyboard shortcuts"
            title="Keyboard shortcuts (?)"
          >
            <Keyboard className="size-4" />
          </button>
          <form action={logout}>
            <button
              type="submit"
              className="grid size-7 place-items-center rounded-md text-subtle hover:bg-surface-3 hover:text-fg"
              aria-label="Log out"
              title="Log out"
            >
              <LogOut className="size-4" />
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}

function LocalClock() {
  const now = useNow();
  if (now === 0) return <span className="num w-[68px] text-xs text-subtle" />;
  const d = new Date(now);
  const t = d.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit", second: "2-digit", timeZone: "Europe/Berlin" });
  return (
    <span className="num hidden text-xs text-muted sm:inline" title="Munich time">
      <span className="font-sans text-subtle">MUC </span>
      {t}
    </span>
  );
}

const SHORTCUTS: [string, string][] = [
  ["/", "Focus search"],
  ["j / k", "Next / previous session"],
  ["Enter", "Open selected session"],
  ["g l", "Live"],
  ["g m", "Mailbox"],
  ["g c", "CRM"],
  ["g o", "Globe"],
  ["g i", "LLM calls"],
  ["g t", "Tariffs"],
  ["g s", "Settings"],
  ["?", "This sheet"],
];

export function AppShell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);

  useGoSequence(Object.fromEntries(NAV.map((n) => [n.key, () => router.push(n.href)])));
  useKeys({
    "/": (e) => {
      if (focusSearch()) e.preventDefault();
    },
    "?": () => setHelpOpen((v) => !v),
  });

  // Close the mobile drawer whenever the route changes.
  const [lastPath, setLastPath] = useState(pathname);
  if (pathname !== lastPath) {
    setLastPath(pathname);
    setMenuOpen(false);
  }

  useEffect(() => {
    document.body.style.overflow = menuOpen ? "hidden" : "";
  }, [menuOpen]);

  const current = NAV.find((n) => isActive(pathname, n.href));

  return (
    <div className="flex h-dvh overflow-hidden">
      {/* Desktop sidebar */}
      <aside className="hidden w-[212px] shrink-0 flex-col gap-5 border-r border-line bg-surface px-3 py-4 md:flex">
        <Link href="/" className="px-1.5">
          <Logo />
        </Link>
        <NavLinks />
        <SidebarFooter onShortcuts={() => setHelpOpen(true)} />
      </aside>

      {/* Mobile drawer */}
      {menuOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          <button className="absolute inset-0 bg-black/50" aria-label="Close menu" onClick={() => setMenuOpen(false)} />
          <aside className="relative flex h-full w-[240px] animate-slide-in flex-col gap-5 border-r border-line bg-surface px-3 py-4">
            <div className="flex items-center justify-between px-1.5">
              <Logo />
              <button onClick={() => setMenuOpen(false)} className="text-subtle" aria-label="Close menu">
                <X className="size-4" />
              </button>
            </div>
            <NavLinks onNavigate={() => setMenuOpen(false)} />
            <SidebarFooter onShortcuts={() => setHelpOpen(true)} />
          </aside>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-3 border-b border-line bg-surface/80 px-3 backdrop-blur sm:px-5">
          <button onClick={() => setMenuOpen(true)} className="text-muted md:hidden" aria-label="Open menu">
            <Menu className="size-5" />
          </button>
          <div className="md:hidden">
            <Logo collapsed />
          </div>
          <div className="flex min-w-0 items-center gap-2 text-[13px]">
            <span className="hidden text-subtle sm:inline">Ops</span>
            <span className="hidden text-subtle sm:inline">/</span>
            <span className="truncate font-medium">{pathname.startsWith("/sessions") ? "Session" : pathname.startsWith("/admin") ? "Admin" : (current?.label ?? "")}</span>
          </div>
          <div className="ml-auto flex items-center gap-3">
            <LocalClock />
            {MOCK_MODE && (
              <span className="hidden rounded-md border border-violet/25 bg-violet/10 px-1.5 py-0.5 text-2xs font-semibold tracking-wide text-violet uppercase sm:inline">
                Demo
              </span>
            )}
            <AutopilotIndicator />
            <ConnectionIndicator />
          </div>
        </header>
        <main id="main" className="dot-grid min-h-0 flex-1 overflow-y-auto">
          {children}
        </main>
      </div>

      <Modal open={helpOpen} onClose={() => setHelpOpen(false)} title="Keyboard shortcuts">
        <ul className="grid grid-cols-1 gap-y-2 text-[13px]">
          {SHORTCUTS.map(([keys, label]) => (
            <li key={keys} className="flex items-center justify-between">
              <span className="text-muted">{label}</span>
              <span className="flex gap-1">
                {keys.split(" ").map((k) => (
                  <Kbd key={k}>{k}</Kbd>
                ))}
              </span>
            </li>
          ))}
        </ul>
      </Modal>
    </div>
  );
}
