import json
import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from .pricing import CARRIERS, extract


def now():
    return datetime.now(UTC)


def timestamp(value):
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, UTC)
    dt = datetime.fromisoformat(value)
    return dt if dt.tzinfo else dt.replace(tzinfo=UTC)


class Store:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS cursor (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL);
                INSERT OR IGNORE INTO cursor VALUES(1,0);
                CREATE TABLE IF NOT EXISTS mail (
                    id INTEGER PRIMARY KEY, root INTEGER NOT NULL, sender TEXT, subject TEXT, body TEXT,
                    received TEXT, kind TEXT, fields TEXT, attachments TEXT, stage TEXT NOT NULL DEFAULT 'review',
                    proposal TEXT, booking TEXT
                );
                CREATE TABLE IF NOT EXISTS deliveries (
                    action TEXT PRIMARY KEY, state TEXT NOT NULL, response TEXT
                );
            """)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def cursor(self):
        with self.connect() as db:
            return db.execute("SELECT value FROM cursor").fetchone()[0]

    def ingest(self, page):
        with self.connect() as db:
            for m in page["messages"]:
                sender = (
                    m.get("from")
                    or m.get("from_address")
                    or m.get("sender")
                    or m.get("from_email")
                    or m.get("from_addr")
                    or m.get("sender_email")
                    or ""
                )
                subject, body = m.get("subject", ""), m.get("body", "")
                parent = m.get("in_reply_to")
                refs = m.get("references") or []
                root = int(refs[0] if refs else parent or m["id"])
                carrier = next((c for c in CARRIERS if c["email"] == sender), None)
                if carrier:
                    kind = "carrier"
                elif sender in ("ratedesk@hackloop.mail", "tracking@hackloop.mail"):
                    kind = sender.split("@")[0]
                elif parent or refs:
                    kind = "follow-up"
                else:
                    kind = "request"
                received = next(
                    (
                        m[k]
                        for k in (
                            "sent_at",
                            "created_at",
                            "received_at",
                            "timestamp",
                            "date",
                            "sent_at_utc",
                            "created_at_utc",
                            "ts",
                        )
                        if m.get(k)
                    ),
                    None,
                )
                try:
                    received = timestamp(received).isoformat()
                except (TypeError, ValueError, AttributeError, OverflowError):
                    received = None
                db.execute(
                    """INSERT OR IGNORE INTO mail
                    (id,root,sender,subject,body,received,kind,fields,attachments,stage)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    (
                        m["id"],
                        root,
                        sender,
                        subject,
                        body,
                        received,
                        kind,
                        json.dumps(extract(subject + "\n" + body)),
                        json.dumps(m.get("attachments") or []),
                        "new" if kind == "request" else "review",
                    ),
                )
            db.execute(
                "UPDATE cursor SET value=max(value,?)", (int(page["next_since_id"]),)
            )

    def messages(self):
        with self.connect() as db:
            rows = db.execute("SELECT * FROM mail ORDER BY id DESC").fetchall()
            actions = {
                r["action"]: r["state"]
                for r in db.execute("SELECT action,state FROM deliveries")
            }
        result = []
        for row in rows:
            item = dict(row)
            for key in ("fields", "attachments", "proposal", "booking"):
                if item[key] is not None:
                    item[key] = json.loads(item[key])
            item["delivery"] = (
                actions.get(f"quote:{item['root']}")
                if item["kind"] == "request"
                else None
            )
            item["reply_delivery"] = actions.get(f"reply:{item['id']}")
            prefix = f"bid:{item['id']}:"
            item["bid_requests"] = {
                key.removeprefix(prefix): state
                for key, state in actions.items()
                if key.startswith(prefix)
            }
            result.append(item)
        return result

    def message(self, mid):
        with self.connect() as db:
            row = db.execute("SELECT * FROM mail WHERE id=?", (mid,)).fetchone()
        if row is None:
            raise KeyError(mid)
        return dict(row)

    def persist_proposal(self, mid, fields, proposal):
        with self.connect() as db:
            modified = db.execute(
                """UPDATE mail SET fields=?,proposal=?,stage=? WHERE id=?
                AND NOT EXISTS(SELECT 1 FROM deliveries WHERE action='quote:' || mail.root)""",
                (
                    json.dumps(fields),
                    json.dumps(proposal),
                    "ready" if proposal["offer"] else "review",
                    mid,
                ),
            )
            return bool(modified.rowcount)

    def reserve(self, mid, action, revision=None):
        """Claim before POST. A crash or lost acknowledgment never triggers a second send."""
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if revision:
                row = db.execute(
                    "SELECT proposal FROM mail WHERE id=?", (mid,)
                ).fetchone()
                if not row or not row[0] or json.loads(row[0])["revision"] != revision:
                    return False
            return bool(
                db.execute(
                    "INSERT OR IGNORE INTO deliveries VALUES(?, 'pending', NULL)",
                    (action,),
                ).rowcount
            )

    def finish(self, action, state, result=None):
        with self.connect() as db:
            if state == "rejected":
                db.execute("DELETE FROM deliveries WHERE action=?", (action,))
            else:
                db.execute(
                    "UPDATE deliveries SET state=?, response=? WHERE action=?",
                    (state, json.dumps(result), action),
                )
