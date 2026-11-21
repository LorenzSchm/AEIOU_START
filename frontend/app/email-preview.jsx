"use client";

import { createContext, useContext, useLayoutEffect, useRef } from "react";

const EmailTemplate = createContext("");
const htmlEntities = { "&": "&amp;", "<": "&lt;", ">": "&gt;" };

export function EmailTemplateProvider({ template, children }) {
  return <EmailTemplate value={template}>{children}</EmailTemplate>;
}

const escape = (text) => text.replace(/[&<>]/g, (character) => htmlEntities[character]);

// Same plane separator as backend/cloudline/cloudline_renderer.py.
const dotted =
  '<span style="display:inline-block;width:9px;margin:0 2px;border-top:1px dotted #607889;vertical-align:.25em;"></span>';
const plane =
  '<span role="img" aria-label="→" style="color:#405a6b;font-family:\'Apple Symbols\',\'Segoe UI Symbol\',\'DejaVu Sans\',sans-serif;' +
  "font-variant-emoji:text;font-size:.74em;font-weight:400;letter-spacing:0;display:inline-block;white-space:nowrap;vertical-align:.16em;\">" +
  `${dotted}&#x2708;&#xFE0E;${dotted}</span>`;

function laneOf(fields) {
  const ends = [fields?.origin, fields?.destination].map((code) => code?.trim().toUpperCase());
  return ends.every(Boolean) ? ends.map(escape).join(` ${plane} `) : "Freight offer";
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
    .replace("$lane", () => laneOf(fields))
    .replace("$cloud_image_url", "/mail-designs/clouds.webp")
    .replace("$message_html", () => `<div style="white-space: pre-wrap;">${escape(body)}</div>`);

  useLayoutEffect(() => {
    const el = host.current;
    if (!el) return;
    const root = el.shadowRoot || el.attachShadow({ mode: "open" });
    const doc = new DOMParser().parseFromString(html, "text/html");
    const bodyStyle = (doc.body.getAttribute("style") || "").replace(/"/g, "&quot;");
    // The template's responsive rules are written for an email client's viewport. Inside the page
    // they'd test the browser window, so the compact layout would never apply in the narrow case
    // panel. Rewrite width media queries as container queries against the preview's own width.
    const headStyles = [...doc.head.querySelectorAll("style")]
      .map((s) => s.outerHTML.replace(/@media\s*\(\s*(max|min)-width/g, "@container mail ($1-width"))
      .join("");
    root.innerHTML = `<style>:host{display:block}*{box-sizing:border-box}.mail-root{container:mail / inline-size}</style>${headStyles}<div class="mail-root" style="${bodyStyle}">${doc.body.innerHTML}</div>`;
  }, [html]);

  return (
    <figure className="email-preview">
      <figcaption>Cloudline preview · Mailbox sends plain text</figcaption>
      <div className="email-frame" ref={host} role="document" aria-label="AEIOU email template preview" />
    </figure>
  );
}
