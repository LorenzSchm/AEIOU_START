"use client";

import "./timeline.css";

const OUTCOME_WORD = {
  done: "Done",
  active: "In progress",
  failed: "Needs attention",
};

function summary(steps) {
  const visible = steps.filter((s) => s.status !== "skipped");
  const done = visible.filter((s) => s.status === "done").length;
  const failed = steps.find((s) => s.status === "failed");
  const active = steps.find((s) => s.status === "active");
  const lastDone = [...steps].reverse().find((s) => s.status === "done");
  const head = failed
    ? failed.label + " needs attention"
    : active
      ? active.label + " in progress"
      : lastDone?.label || "Not started";
  return `${head}, ${done} of ${visible.length} steps`;
}

/** Compact horizontal progress: one dot per step, joined by a hairline. Props: { steps } */
export default function ProcessStrip({ steps = [], className = "" }) {
  const label = summary(steps);
  return (
    <div className={"ps " + className} role="img" aria-label={label} title={label}>
      {steps.map((s, i) => {
        const prev = steps[i - 1];
        const lit = prev && prev.status === "done" && s.status !== "pending";
        return (
          <span key={s.key} className={"ps-seg ps-" + s.status} title={s.label + (OUTCOME_WORD[s.status] ? " · " + OUTCOME_WORD[s.status] : "")}>
            {i > 0 && <span className={"ps-line" + (lit ? " ps-line-lit" : "")} aria-hidden="true" />}
            <span className="ps-dot" aria-hidden="true">
              {s.status === "failed" && <span className="ps-bang">!</span>}
            </span>
          </span>
        );
      })}
    </div>
  );
}
