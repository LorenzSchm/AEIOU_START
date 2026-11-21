# sim5u: a simulated 5U API

A local stand-in for the 5U hackathon API, so the whole forwarder can be tested end to end without any traffic to the real event. It serves the same paths and payloads (`docs/5u-openapi.json`, plus the shapes recorded from the live API). The backend runs against it unchanged; only `FIVEU_BASE_URL` differs.

```sh
cd forwarder
docker compose -f docker-compose.yml -f docker-compose.sim.yml up -d --build
open http://localhost:8090   # sim board: rounds, verdicts, points, mail traffic, chaos buttons
open http://localhost:3000   # our control panel
```

Sim mode uses its own database volume (`pgdata-sim`) and the fake key `sim-key`. The backend refuses to start unless `FIVEU_BASE_URL` points to the simulator. LLM calls go straight to OpenAI (`LLM_BASE_URL`/`LLM_API_KEY` from `.env`), never to the 5U gateway.

## What it simulates
| Part | Behaviour |
|---|---|
| Customers | A new RFQ every `SIM_RFQ_INTERVAL_SECONDS` (default 360, or `POST /sim/rfq`). The mix is re-issued RFQs from the real mail history (`fixtures/mail-history.json`) and generated ones: cheapest/fastest, door pickup with a postcode or "we deliver ourselves", EN/DE, volume-heavy cargo, email-only destinations. After a quote the customer books, asks a follow-up, or declines (wording from the history). |
| Carriers | Answer rate requests after `SIM_CARRIER_DELAY_MIN..MAX` seconds, in the history's formats: offers with `R-…` ids (bookable via the portal), "We do not serve this lane … We fly …" declines, trucker offers with `TRK-…`, refusals to counteroffers, and booking by email. |
| Rate desk | Prices lanes that aren't on the portal. |
| Portal | Lanes, brackets and rates with the real 10/min fixed window and `x-ratelimit-*`/`Retry-After`. Rate ids are valid 15 min. Bookings return 201/200/404/409/410/503, followed by a "Booking BK-… confirmed" mail. |
| Tracking desk | After a booking it sometimes asks about the shipment (or `POST /sim/tracking`). A correct answer (booking ref + status) earns +10. |
| Board | `/v1/board/me` and `/v1/board` in the live shape. |
| Chaos | `POST /sim/chaos` (portal 503 share, booking 503 share, rate limit, odd reply phrasing, carrier delay, RFQ interval) and `POST /sim/chaos/tariff` (a new monthly tariff mail with an attachment and changed rates). |

## Scoring (`app/scoring.py`)
- Only the first reply in the customer thread that states an amount counts.
- It must contain exactly one total, in EUR.
- **Accepted** within ±5% of the best price.
- **Points:**
  - Price: 45 at the best price, falling linearly to 0 at +5%.
  - Time: 15 within 2 min, falling linearly to 0 at 6 min.
  - Cost: always 40, because the sim can't see LLM spend.
- **Reasons:** `too_high`, `too_low`, `late`, `no_quote`, `no_total`, `multiple_totals`, `wrong_currency`.

## How the best price is set (`World.truth`)
- **Which airlines count:** every airline flying the lane.
  - Tariff-sheet airlines and portal airlines count at their best price including "bill at a higher break", since a forwarder can find that.
  - Email-only airlines count at their own-bracket bid, since that is all they offer.
- **Door pickup:** adds the cheapest trucker for the postcode zone.
- **By mode:** `cheapest` takes the lowest total; `fastest` takes the fewest transit days, then the lowest total.

The tariff-sheet airlines are priced from `fixtures/tariffs.json`, which is exported from our own parser (the backend's `/api/tariffs`). The sim's truth therefore agrees with our pricing engine by construction. Real price accuracy is validated by the backend's unit tests (hand-calculated per sheet) and by the live board. What the sim validates is the flow, timing, mail formats, booking, tracking and robustness under chaos.

## Develop
```sh
cd forwarder/sim
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest
SIM_RFQ_INTERVAL_SECONDS=60 .venv/bin/uvicorn app.main:app --port 8090
```
Mail ids are time-based and keep growing across restarts, so a backend that kept its `since_id` still sees new mail. Round numbers restart at 1, so start with a fresh sim database for clean board matching: `docker compose -f docker-compose.yml -f docker-compose.sim.yml down && docker volume rm forwarder_pgdata-sim`.
