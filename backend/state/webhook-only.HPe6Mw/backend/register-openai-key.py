#!/usr/bin/env python3
"""Register the local staging key with the team's 5U gateway."""
import http.client
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = ROOT / "backend/state/openai.key"
ENDPOINT = "/v1/gateway/openai-key"


def team_key():
    if key := os.getenv("TEAM_API_KEY"):
        return key
    for line in (ROOT / ".env").read_text().splitlines():
        name, _, value = line.partition("=")
        if name.strip() == "TEAM_API_KEY":
            return value.strip().strip("'\u0022")
    raise SystemExit("Set TEAM_API_KEY in the repo-root .env first.")


def gateway(method, team, key=None):
    conn = http.client.HTTPSConnection("api.start-hack.5u.ai", timeout=20)
    try:
        conn.request(method, ENDPOINT, body=json.dumps({"api_key": key}) if key else None,
                     headers={"X-API-Key": team, "Content-Type": "application/json"})
        response = conn.getresponse()
        if not 200 <= response.status < 300:
            raise SystemExit(f"5U returned HTTP {response.status}. Check the team key and OpenAI key.")
        body = response.read()
        return json.loads(body) if method == "GET" else None
    finally:
        conn.close()


def main():
    team = team_key()
    if gateway("GET", team).get("set") is True:
        print("5U already has an OpenAI key registered for this team.")
        return
    key = KEY_FILE.read_text().strip() if KEY_FILE.exists() else ""
    if not key.startswith("sk-") or any(c.isspace() for c in key):
        raise SystemExit(f"Put the raw OpenAI API key on one line in {KEY_FILE}, then rerun.")
    gateway("POST", team, key)
    if gateway("GET", team).get("set") is not True:
        raise SystemExit("5U has not confirmed registration. Check gateway status before retrying.")
    print("5U confirmed the OpenAI key is registered. The worker is ready without a restart.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, http.client.HTTPException, ValueError):
        sys.exit("Registration could not be verified. Check connectivity and rerun to read 5U status.")
