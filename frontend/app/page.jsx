"use client";

import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import Icon from "./components/ui/Icon";
import Overview from "./components/overview/Overview";
import Queue from "./components/queue/Queue";
import CaseSheet from "./components/case/CaseSheet";
import ConfirmDialog from "./components/ui/ConfirmDialog";
import { useDispatchData } from "./lib/api";
import { clock, needsAttention } from "./lib/format";
import { spring } from "./lib/motion";

const NAV = [
  { id: "overview", label: "Overview", icon: "overview" },
  { id: "queue", label: "Queue", icon: "queue" },
];

function readUrl() {
  if (typeof window === "undefined") return { view: "overview", caseId: null, scope: "attention" };
  const p = new URLSearchParams(window.location.search);
  return {
    view: p.get("view") === "queue" ? "queue" : "overview",
    caseId: p.get("case") ? Number(p.get("case")) : null,
    scope: ["attention", "requests", "all"].includes(p.get("scope")) ? p.get("scope") : "attention",
  };
}

function modeLabel(status) {
  if (!status) return "—";
  if (!status.auto_send) return "Operator review";
  if (status.start_at && Date.parse(status.start_at) > Date.now())
    return "Armed · " + new Date(status.start_at).toLocaleTimeString([], {
      timeZone: "Europe/Berlin", hour12: false, hour: "2-digit", minute: "2-digit",
    }) + " Berlin";
  return "Automatic";
}

/**
 * A view that stays mounted while hidden. On re-entry its top-level glass panels fade/slide in
 * individually. Animating the panels themselves (not a wrapper) keeps backdrop-filter working:
 * an ancestor with opacity < 1 would turn the blur off until the animation ends.
 */
function KeptView({ name, active, children }) {
  const ref = useRef(null);
  const first = useRef(true);
  useLayoutEffect(() => {
    if (!active) return;
    if (first.current) {
      first.current = false; // first mount: components run their own entrance
      return;
    }
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const panels = [...ref.current.querySelectorAll(".glass")].filter((g) => !g.parentElement.closest(".glass"));
    panels.forEach((g, i) =>
      g.animate(
        [{ opacity: 0, transform: "translateY(8px)" }, { opacity: 1, transform: "translateY(0)" }],
        { duration: 320, easing: "cubic-bezier(0.22, 1, 0.36, 1)", delay: Math.min(i, 8) * 30, fill: "backwards" },
      ),
    );
  }, [active]);
  return (
    // Hidden views stay laid out (visibility, not display:none) so the globe can size itself and
    // build its land layer in the background before the first visit.
    <div ref={ref} className="view" data-view={name} data-hidden={active ? undefined : ""} aria-hidden={!active || undefined} inert={!active || undefined}>
      {children}
    </div>
  );
}

/**
 * Primary navigation, memoised: the 2 s status poll re-renders the sidebar footer, and an
 * un-memoised layoutId pill would make Motion re-measure its projection tree on every poll.
 */
const NavList = memo(function NavList({ view, attention, onView }) {
  return (
    <ul>
      {NAV.map((n) => (
        <li key={n.id}>
          <button className={"nav-item" + (view === n.id ? " active" : "")}
                  aria-current={view === n.id ? "page" : undefined}
                  onClick={() => onView(n.id)}>
            {view === n.id && <motion.span layoutId="nav-pill" className="nav-pill" transition={spring} />}
            <Icon name={n.icon} />
            <span>{n.label}</span>
            {n.id === "queue" && attention > 0 && <b className="nav-badge" aria-label={attention + " need attention"}>{attention}</b>}
          </button>
        </li>
      ))}
    </ul>
  );
});

export default function Page() {
  const { data, error, refresh } = useDispatchData();
  const [nav, setNav] = useState({ view: "overview", caseId: null, scope: "attention" });

  useEffect(() => {
    setNav(readUrl());
    const onPop = () => setNav(readUrl());
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  const navRef = useRef(nav);
  navRef.current = nav;
  // History is updated outside the state updater: Next patches pushState and would
  // otherwise update its Router while React renders Page.
  const go = useCallback((patch, { replace = false } = {}) => {
    const next = { ...navRef.current, ...patch };
    const p = new URLSearchParams();
    if (next.view !== "overview") p.set("view", next.view);
    if (next.caseId) p.set("case", next.caseId);
    if (next.scope !== "attention") p.set("scope", next.scope);
    const url = window.location.pathname + (p.size ? "?" + p : "");
    if (url !== window.location.pathname + window.location.search)
      window.history[replace ? "replaceState" : "pushState"](null, "", url);
    navRef.current = next;
    setNav(next);
  }, []);

  // Unsaved manual edits in the open case: confirm before switching or closing.
  const dirtyRef = useRef(false);
  const onDirtyChange = useCallback((d) => {
    dirtyRef.current = d;
  }, []);
  const [pending, setPending] = useState(null);
  const guardedGo = useCallback(
    (patch, opts) => {
      const leavesCase = "caseId" in patch && patch.caseId !== navRef.current.caseId;
      if (leavesCase && dirtyRef.current) setPending({ patch, opts });
      else go(patch, opts);
    },
    [go],
  );
  const confirmLeave = useCallback(() => {
    dirtyRef.current = false;
    const p = pending;
    setPending(null);
    if (p) go(p.patch, p.opts);
  }, [go, pending]);
  const cancelLeave = useCallback(() => setPending(null), []);

  // Views stay mounted after their first visit: switching back is instant (no globe rebuild).
  const seen = useRef(new Set());
  seen.current.add(nav.view);
  // Pre-build the Overview (and its WebGL globe) in the background once the app is idle, so the
  // first switch to Overview is instant instead of freezing ~1.5 s on the globe's land layer.
  const [, preloaded] = useState(false);
  useEffect(() => {
    if (!data || seen.current.has("overview")) return;
    const idle = window.requestIdleCallback ?? ((fn) => setTimeout(fn, 1200));
    const cancel = window.cancelIdleCallback ?? clearTimeout;
    const id = idle(() => {
      seen.current.add("overview");
      preloaded(true);
    }, { timeout: 3000 });
    return () => cancel(id);
  }, [data]);

  const messages = data?.messages || [];
  const current = messages.find((m) => m.id === nav.caseId);
  const attention = messages.filter(needsAttention).length;
  const bad = error || data?.status?.error;
  const webhookMode = data?.status?.webhook_enabled;
  const lastDelivery = webhookMode ? data.status.last_webhook : data?.status?.last_poll;
  const closeCase = useCallback(() => guardedGo({ caseId: null }), [guardedGo]);
  // Stable callbacks so memoised views (Queue, Overview, rows) don't re-render on every parent render.
  const openCase = useCallback((id, opts) => guardedGo({ caseId: id }, opts), [guardedGo]);
  const setScope = useCallback((scope) => go({ scope }), [go]);
  const setView = useCallback((view) => go({ view }), [go]);

  return (
    <div className="app">
      <a href="#main" className="skip">Skip to content</a>
      <nav className="sidebar glass" aria-label="Primary">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <span>AEIOU<small>Dispatch</small></span>
        </div>
        <NavList view={nav.view} attention={attention} onView={setView} />
        <div className="sidebar-foot">
          <span className={"signal" + (bad ? " bad" : data ? " ok" : "")}>
            <i aria-hidden="true" />
            {bad ? "Connection issue" : webhookMode ? "Webhook mode" : lastDelivery ? "Mailbox live" : "Connecting…"}
          </span>
          <dl>
            <div><dt>Mode</dt><dd>{modeLabel(data?.status)}</dd></div>
            <div><dt>{webhookMode ? "Last webhook" : "Last poll"}</dt><dd className="mono">{clock(lastDelivery)}</dd></div>
            <div><dt>Inbox</dt><dd className="mono">{data ? messages.length : "—"}</dd></div>
            <div><dt>Kit rates</dt><dd className="mono">{data?.status?.tariffs ?? "—"}</dd></div>
          </dl>
        </div>
      </nav>

      <main id="main" className={"content view-" + nav.view + (current ? " has-case" : "")}>
        <header className="page-head">
          <div>
            <div className="eyebrow">Air export · Live wave</div>
            <h1>{nav.view === "queue" ? "Queue" : "Overview"}</h1>
          </div>
        </header>

        <AnimatePresence>
          {bad && (
            <motion.div className="banner banner-bad" role="alert" initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
              <Icon name="alert" size={16} />
              <span>{bad}</span>
            </motion.div>
          )}
        </AnimatePresence>

        {!data ? (
          <div className="skeleton-grid" aria-busy="true" aria-label="Loading">
            {Array.from({ length: 6 }).map((_, i) => <div key={i} className="skeleton glass" />)}
          </div>
        ) : (
          <>
            {seen.current.has("overview") && (
              <KeptView name="overview" active={nav.view === "overview"}>
                <Overview messages={messages} active={nav.view === "overview"} onOpen={openCase} />
              </KeptView>
            )}
            {seen.current.has("queue") && (
              <KeptView name="queue" active={nav.view === "queue"}>
                <Queue messages={messages} selectedId={nav.caseId} scope={nav.scope} active={nav.view === "queue"}
                       setScope={setScope} onSelect={openCase} />
              </KeptView>
            )}
          </>
        )}
      </main>

      <AnimatePresence>
        {current && nav.view === "overview" && (
          <motion.div key="scrim" className="scrim" onClick={closeCase}
                      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} />
        )}
      </AnimatePresence>
      <AnimatePresence>
        {current && (
          <CaseSheet key="case-sheet" m={current} messages={messages} directory={data.directory}
                     refresh={refresh} onClose={closeCase} onDirtyChange={onDirtyChange} />
        )}
      </AnimatePresence>
      <ConfirmDialog
        open={Boolean(pending)}
        title="Discard your edits?"
        message="This case has manual changes that weren't repriced yet. Leaving now discards them."
        cancelLabel="Keep editing"
        confirmLabel="Discard"
        onCancel={cancelLeave}
        onConfirm={confirmLeave}
      />
    </div>
  );
}
