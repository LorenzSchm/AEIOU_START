"use client";

import { createContext, useContext, useLayoutEffect, useRef } from "react";

const EmailTemplate = createContext("");
const htmlEntities = { "&": "&amp;", "<": "&lt;", ">": "&gt;" };

export function EmailTemplateProvider({ template, children }) {
  return <EmailTemplate value={template}>{children}</EmailTemplate>;
}

const escape = (text) => text.replace(/[&<>]/g, (character) => htmlEntities[character]);

function laneOf(fields) {
  const ends = [fields?.origin, fields?.destination].map((code) => code?.trim().toUpperCase());
  return ends.every(Boolean) ? ends.join(" → ") : ends.find(Boolean) || "Air freight";
}

/**
 * Renders the email template inside a shadow root: styles stay isolated like an iframe,
 * but it paints synchronously — no blank frame, no load event, no height measuring.
 * The body text is escaped; the template itself is our own trusted file.
 */
export default function EmailPreview({ body, fields }) {
  const template = useContext(EmailTemplate);
  const host = useRef(null);
  const html = template
    .replace("$language", "en")
    .replace("$lane", () => escape(laneOf(fields)))
    .replace("$message_html", () => `<div style="white-space: pre-wrap;">${escape(body)}</div>`);

  useLayoutEffect(() => {
    const el = host.current;
    if (!el) return;
    const root = el.shadowRoot || el.attachShadow({ mode: "open" });
    const doc = new DOMParser().parseFromString(html, "text/html");
    const bodyStyle = (doc.body.getAttribute("style") || "").replace(/"/g, "&quot;");
    root.innerHTML = `<style>:host{display:block}*{box-sizing:border-box}</style><div style="${bodyStyle}">${doc.body.innerHTML}</div>`;
  }, [html]);

  return (
    <figure className="email-preview">
      <figcaption>Template preview · Mailbox sends plain text</figcaption>
      <div className="email-frame" ref={host} role="document" aria-label="AEIOU email template preview" />
    </figure>
  );
}
