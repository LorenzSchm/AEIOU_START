"use client";

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import Icon from "./Icon";
import { spring } from "../../lib/motion";

/**
 * Glass listbox replacing the native <select>. Same props shape:
 *   options: [{ value, label, hint? }], value, onChange(value), placeholder, disabled
 * Keyboard: Enter/Space/↓ opens, ↑/↓ move, Enter selects, Esc closes, letters jump.
 * The popover is portalled with fixed positioning so collapsible/scrolling parents never clip it.
 */
export default function Select({ options, value, onChange, placeholder = "Choose", disabled, ariaLabel, id }) {
  const autoId = useId();
  const listId = (id || autoId) + "-list";
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [rect, setRect] = useState(null);
  const trigger = useRef(null);
  const list = useRef(null);
  const reduce = useReducedMotion();
  const selected = options.find((o) => o.value === value);

  const place = useCallback(() => {
    const r = trigger.current?.getBoundingClientRect();
    if (!r) return;
    const below = window.innerHeight - r.bottom;
    const height = Math.min(280, options.length * 40 + 12);
    const up = below < height + 12 && r.top > below;
    setRect({ left: r.left, width: r.width, top: up ? undefined : r.bottom + 6, bottom: up ? window.innerHeight - r.top + 6 : undefined, up });
  }, [options.length]);

  const openList = () => {
    if (disabled) return;
    place();
    setActive(Math.max(0, options.findIndex((o) => o.value === value)));
    setOpen(true);
  };
  const close = (focus = true) => {
    setOpen(false);
    if (focus) trigger.current?.focus({ preventScroll: true });
  };
  const choose = (i) => {
    const o = options[i];
    if (!o) return;
    onChange(o.value);
    close();
  };

  useLayoutEffect(() => {
    if (!open) return;
    const onMove = () => place();
    const onDown = (e) => {
      if (!list.current?.contains(e.target) && !trigger.current?.contains(e.target)) close(false);
    };
    window.addEventListener("resize", onMove);
    window.addEventListener("scroll", onMove, true);
    document.addEventListener("pointerdown", onDown, true);
    return () => {
      window.removeEventListener("resize", onMove);
      window.removeEventListener("scroll", onMove, true);
      document.removeEventListener("pointerdown", onDown, true);
    };
  }, [open, place]);

  useEffect(() => {
    if (open && active >= 0) list.current?.children[active]?.scrollIntoView({ block: "nearest" });
  }, [open, active]);

  const onKeyDown = (e) => {
    if (disabled) return;
    if (!open) {
      if (["ArrowDown", "ArrowUp", "Enter", " "].includes(e.key)) {
        e.preventDefault();
        e.stopPropagation();
        openList();
      }
      return;
    }
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation(); // don't close the case sheet
      close();
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      e.stopPropagation();
      setActive((i) => Math.min(options.length - 1, Math.max(0, i + (e.key === "ArrowDown" ? 1 : -1))));
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      choose(active);
    } else if (e.key === "Tab") {
      close(false);
    } else if (e.key.length === 1) {
      const i = options.findIndex((o) => String(o.label).toLowerCase().startsWith(e.key.toLowerCase()));
      if (i >= 0) setActive(i);
    }
  };

  return (
    <>
      <button
        ref={trigger}
        id={id}
        type="button"
        className={"select" + (open ? " open" : "") + (selected ? "" : " placeholder")}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        aria-label={ariaLabel}
        aria-activedescendant={open && active >= 0 ? listId + "-" + active : undefined}
        disabled={disabled}
        onClick={() => (open ? close() : openList())}
        onKeyDown={onKeyDown}
      >
        <span className="select-value">{selected ? selected.label : placeholder}</span>
        <motion.span className="select-chevron" animate={{ rotate: open ? 180 : 0 }} transition={spring}>
          <Icon name="down" size={16} />
        </motion.span>
      </button>
      {typeof document !== "undefined" &&
        createPortal(
          <AnimatePresence>
            {open && rect && (
              <motion.ul
                ref={list}
                id={listId}
                role="listbox"
                className="select-pop glass glass-strong"
                style={{ left: rect.left, width: Math.max(rect.width, 200), top: rect.top, bottom: rect.bottom, transformOrigin: rect.up ? "bottom" : "top" }}
                initial={reduce ? false : { opacity: 0, scale: 0.97, y: rect.up ? 4 : -4 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={reduce ? undefined : { opacity: 0, scale: 0.98, transition: { duration: 0.12 } }}
                transition={spring}
              >
                {options.map((o, i) => (
                  <li
                    key={String(o.value)}
                    id={listId + "-" + i}
                    role="option"
                    aria-selected={o.value === value}
                    className={(i === active ? "active " : "") + (o.value === value ? "selected" : "")}
                    onPointerEnter={() => setActive(i)}
                    onPointerDown={(e) => e.preventDefault()}
                    onClick={() => choose(i)}
                  >
                    <span className="select-check">{o.value === value && <Icon name="check" size={14} />}</span>
                    <span className="select-label">{o.label}</span>
                    {o.hint && <span className="select-hint">{o.hint}</span>}
                  </li>
                ))}
                {!options.length && <li className="select-empty" role="presentation">No options</li>}
              </motion.ul>
            )}
          </AnimatePresence>,
          document.body,
        )}
    </>
  );
}
