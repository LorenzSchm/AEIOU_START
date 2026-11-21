# Team AEIOU kit card

Fehmi at 5U AI sent this card to Lorenz Walter Schmidt. The first request arrives at **16:00**. Before then, the team can read the API docs, try the portal, and download the start kit.

## Team access

| Resource | Details |
| --- | --- |
| Team mailbox | [aeiou@hackloop.mail](mailto:aeiou@hackloop.mail) |
| API base URL | `https://api.start-hack.5u.ai` |
| API documentation | [Read the API docs](https://api.start-hack.5u.ai/docs) |
| Portal | [Open the team portal](https://start-hack.5u.ai/portal/ui) |
| Start kit | [Local files](start-kit/) · [Download ZIP](https://api.start-hack.5u.ai/kit/start-kit.zip) (rate sheets, carrier list, and pickup zones) |
| Leaderboard | [View the leaderboard](https://api.start-hack.5u.ai/board) |

The mailbox, portal, and LLM gateway share one team API key. This checkout stores it as `TEAM_API_KEY` in the Git-ignored `.env` file at the repo root. For API calls, send either `X-API-Key: <team key>` or `Authorization: Bearer <team key>`.

Keep the key within the team. Everything sent with it counts as team AEIOU activity. If the key is lost, visit the 5U booth for a replacement.

## Mail API

| Action | Method | Path relative to the API base URL |
| --- | --- | --- |
| Poll for messages | GET | `/v1/mailbox/messages?since_id=0` |
| Send a message or reply | POST | `/v1/mailbox/messages` |

## LLM gateway

The card gives a one-time setup command to register the team's own OpenAI API key. Run it from the repo root after replacing `sk-...` with that OpenAI key.

```sh
. ./.env
curl -X POST 'https://api.start-hack.5u.ai/v1/gateway/openai-key' \
  -H "X-API-Key: ${TEAM_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d '{"api_key":"sk-..."}'
```

After registration, configure the OpenAI client with `https://api.start-hack.5u.ai/v1/gateway` as its `base_url` and the team key as its `api_key`.

## Sender details

Both entities list Seidlstraße 26, 80335 München, Germany.

| Entity | Registration | Tax ID | Management |
| --- | --- | --- | --- |
| 5U AI B.V. | Registered office in Rotterdam, Netherlands · KvK 97730726 | VAT DE464837483 | Managing Director Ali Yagizhan Abik |
| 5U AI GmbH | Amtsgericht München HRB 313601 | USt-IdNr. DE464836232 | Geschäftsführer Ali Yagizhan Abik and Fehmi Sener |
