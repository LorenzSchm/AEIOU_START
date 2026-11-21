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

export default function EmailPreview({ body }) {
  const template = useContext(EmailTemplate);
  const escapedBody = body.replace(/[&<>]/g, (character) => htmlEntities[character]);
  const html = template
    .replace("$language", "en")
    .replace("$message_html", () => `<div style="white-space: pre-wrap;">${escapedBody}</div>`);

  return (
    <figure className="email-preview">
      <figcaption>Template preview · Mailbox sends plain text</figcaption>
      <iframe title="AEIOU email template preview" sandbox="allow-same-origin" srcDoc={html} ref={fitEmail} />
    </figure>
  );
}
