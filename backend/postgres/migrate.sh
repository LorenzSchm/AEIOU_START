#!/usr/bin/env bash
# Copies the SQLite ledger into Postgres. Safe to re-run: each table is truncated and reloaded
# inside one transaction, so readers never see a half-copied state.
#
#   DATABASE_URL=postgresql://... backend/postgres/migrate.sh [path/to/dispatch.sqlite3]
#
# Needs only sqlite3 and psql. DATABASE_URL falls back to backend/.env.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
db="${1:-$here/../state/dispatch.sqlite3}"
if [[ -z "${DATABASE_URL:-}" && -f "$here/../.env" ]]; then
  set -a; . "$here/../.env"; set +a
fi
: "${DATABASE_URL:?set DATABASE_URL or add it to backend/.env}"
[[ -f "$db" ]] || { echo "no SQLite file at $db" >&2; exit 1; }

tables=(cursor mail deliveries wave_case tender retry rfq_parse booking_attempt document_check
  board_result truck_bid_parse dg_gateway_check portal_rate_history mailbox_push)

PGOPTIONS='-c client_min_messages=warning' psql "$DATABASE_URL" -q -v ON_ERROR_STOP=1 -f "$here/schema.sql"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Read from a snapshot so a running worker can keep writing.
sqlite3 "$db" ".backup '$tmp/snap.sqlite3'"

{
  echo "BEGIN;"
  (IFS=,; echo "TRUNCATE ${tables[*]};")
  for t in "${tables[@]}"; do
    cols="$(sqlite3 "$tmp/snap.sqlite3" "SELECT group_concat('\"'||name||'\"', ',') FROM pragma_table_info('$t')")"
    sqlite3 -csv -nullvalue '\N' "$tmp/snap.sqlite3" "SELECT $cols FROM \"$t\"" > "$tmp/$t.csv"
    echo "\\copy \"$t\" ($cols) FROM '$tmp/$t.csv' WITH (FORMAT csv, NULL '\\N')"
  done
  echo "COMMIT;"
} > "$tmp/load.sql"

psql "$DATABASE_URL" -q -v ON_ERROR_STOP=1 -f "$tmp/load.sql"

for t in "${tables[@]}"; do
  src="$(sqlite3 "$tmp/snap.sqlite3" "SELECT count(*) FROM \"$t\"")"
  dst="$(psql "$DATABASE_URL" -Atc "SELECT count(*) FROM \"$t\"")"
  printf '%-20s sqlite=%-5s postgres=%-5s %s\n' "$t" "$src" "$dst" "$([[ $src == "$dst" ]] && echo ok || echo MISMATCH)"
done
