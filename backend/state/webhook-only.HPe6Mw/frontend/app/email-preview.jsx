"use client";

import { createContext, useContext } from "react";

const EmailTemplate = createContext("");
const htmlEntities = { "&": "&amp;", "<": "&lt;", ">": "&gt;" };

export function EmailTemplateProvider({ template, children }) {
  return <EmailTemplate value={template}>{children}</EmailTemplate>;
}

function fitEmail(frame) {
  const observer = new ResizeObserver(([entry]) => {
    frame.style.height = `${Math.ceil(entry.contentRect.height)}px`;
  });
  function observeBody() {
    observer.disconnect();
    const body = frame.contentDocument?.body;
    if (body) observer.observe(body);
  }
  frame.addEventListener("load", observeBody);
  return () => {
    frame.removeEventListener("load", observeBody);
    observer.disconnect();
  };
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

export default function EmailPreview({ body, fields }) {
  const template = useContext(EmailTemplate);
  const html = template
    .replace("$language", "en")
    .replace("$lane", () => laneOf(fields))
    .replace("$cloud_image_url", "/mail-designs/clouds.webp")
    .replace("$message_html", () => `<div style="white-space: pre-wrap;">${escape(body)}</div>`);

  return (
    <figure className="email-preview">
      <figcaption>Cloudline preview · Mailbox sends plain text</figcaption>
      <iframe title="AEIOU email template preview" sandbox="allow-same-origin" srcDoc={html} ref={fitEmail} />
    </figure>
  );
}
