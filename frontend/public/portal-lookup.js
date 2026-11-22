// Run in the DevTools console on https://start-hack.5u.ai/portal/ui after the page has loaded.
// Does one rate lookup through the page's two-step protocol and prints the visible result.
(async () => {
  const ask = (label, fallback = "") => (prompt(label, fallback) || "").trim();
  const lookup = {
    origin: ask("Origin (IATA)").toUpperCase(),
    destination: ask("Destination (IATA)").toUpperCase(),
    airline: ask("Airline (code or name)"),
    weight: ask("Weight (kg)"),
    volume: ask("Volume (cbm)"),
    key: ask("Team key"),
  };

  const load = await fetch(location.pathname, { method: "POST", headers: { "X-Portal-Step": "load" } });
  if (!load.ok) return console.error("Challenge load failed", load.status, await load.text());
  const challenge = await load.json();

  const form = new FormData();
  for (const { name, label, kind } of challenge.fields) {
    const l = label.toLowerCase();
    form.append(name, kind === "password" ? lookup.key
      : l.startsWith("origin") ? lookup.origin
      : l.startsWith("destination") ? lookup.destination
      : l.startsWith("weight") ? lookup.weight
      : l.startsWith("volume") ? lookup.volume
      : kind === "airline" || l.startsWith("airline") ? lookup.airline
      : "");
  }
  if (challenge.consent?.name) form.append(challenge.consent.name, challenge.consent.value);

  // Show the sum and let the operator type the answer.
  const img = Object.assign(document.createElement("img"), { src: challenge.answer.image });
  Object.assign(img.style, { position: "fixed", top: "16px", left: "16px", zIndex: 99999, border: "2px solid #333", background: "#fff" });
  document.body.append(img);
  await new Promise((r) => setTimeout(r, 50));
  const answer = ask("Answer to the sum shown top-left");
  img.remove();
  form.append(challenge.answer.name, answer);

  const reply = await fetch(location.pathname, { method: "POST", headers: { "X-Portal-Challenge": challenge.id }, body: form });
  const body = await reply.text();
  if (!reply.ok) return console.error("Lookup failed", reply.status, body);

  // Render the reply so the page's own style rules hide the decoy characters, then read only what is shown.
  let html = body;
  try {
    const json = JSON.parse(body);
    if (json.rate_id) return console.table(json);
    html = json.html ?? json.content ?? body;
  } catch {}
  const box = document.createElement("div");
  box.style.cssText = "position:fixed;left:-10000px;top:0;width:800px";
  box.innerHTML = html;
  document.body.append(box);
  const text = box.innerText;
  box.remove();
  console.log(text);
  const rateId = text.match(/R-[A-Za-z0-9_-]{16}/)?.[0];
  console.log("Rate ID:", rateId ?? "not found, read it from the text above");
})();
