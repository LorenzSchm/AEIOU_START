"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import Icon from "./Icon";
import { spring } from "../../lib/motion";

/**
 * Glass alert dialog (Apple-style): title, short message, safe action focused by default,
 * destructive action in red. Esc / scrim click = cancel. Blocks app shortcuts while open.
 */
export default function ConfirmDialog({ open, title, message, confirmLabel, cancelLabel = "Cancel", confirmStyle = "destructive", onConfirm, onCancel }) {
  const [mounted, setMounted] = useState(false);
  const reduce = useReducedMotion();
  const cancelRef = useRef(null);
  const confirmRef = useRef(null);
  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement;
    requestAnimationFrame(() => cancelRef.current?.focus());
    const onKey = (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        onCancel();
      } else if (e.key === "Tab") {
        // keep focus inside the two buttons
        e.preventDefault();
        (document.activeElement === cancelRef.current ? confirmRef.current : cancelRef.current)?.focus();
      } else if (e.key.startsWith("Arrow")) {
        e.stopPropagation();
        e.preventDefault();
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => {
      window.removeEventListener("keydown", onKey, true);
      if (previous instanceof HTMLElement) previous.focus({ preventScroll: true });
    };
  }, [open, onCancel]);

  if (!mounted) return null;
  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="dialog-layer" key="dialog">
          <motion.div className="dialog-scrim" onClick={onCancel}
                      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, transition: { duration: 0.14 } }} />
          <motion.div
            className="dialog glass glass-strong"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="dialog-title"
            aria-describedby="dialog-message"
            initial={reduce ? false : { opacity: 0, scale: 0.94 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={reduce ? undefined : { opacity: 0, scale: 0.97, transition: { duration: 0.14 } }}
            transition={spring}
          >
            <span className="dialog-icon"><Icon name="alert" size={20} /></span>
            <h2 id="dialog-title">{title}</h2>
            <p id="dialog-message">{message}</p>
            <div className="dialog-actions">
              <button ref={cancelRef} className="btn" onClick={onCancel}>{cancelLabel}</button>
              <button ref={confirmRef} className={`btn btn-${confirmStyle}`} onClick={onConfirm}>{confirmLabel}</button>
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
