-- Core schema. See ARCHITECTURE.md §7. New tables/columns go into new migrations (V2__..., V3__...).

CREATE TABLE poller_state (
    key       TEXT PRIMARY KEY,
    since_id  BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE setting (
    key        TEXT PRIMARY KEY,
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

-- Reference data ------------------------------------------------------------

CREATE TABLE carrier (
    id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name   TEXT NOT NULL,
    code   TEXT,                       -- two-letter airline code once known (portal / tariff)
    kind   TEXT NOT NULL CHECK (kind IN ('airline', 'trucker')),
    email  TEXT NOT NULL UNIQUE,
    serves TEXT[] NOT NULL DEFAULT '{}' -- IATA destinations (airline) or 2-digit zip zones (trucker)
);

CREATE TABLE pickup_zone (
    zone    TEXT PRIMARY KEY,
    area    TEXT NOT NULL,
    airport TEXT NOT NULL
);

CREATE TABLE airport (
    iata    TEXT PRIMARY KEY,
    name    TEXT,
    city    TEXT,
    country TEXT,
    lat     DOUBLE PRECISION NOT NULL,
    lon     DOUBLE PRECISION NOT NULL
);

-- Sessions, mail, events ----------------------------------------------------

CREATE TABLE session (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref       TEXT NOT NULL UNIQUE,
    type             TEXT NOT NULL,   -- RFQ | TRACKING | TARIFF | OTHER
    status           TEXT NOT NULL,   -- see SessionStatus
    customer_addr    TEXT,
    subject          TEXT,
    mode             TEXT,            -- cheapest | fastest
    needs_human      BOOLEAN NOT NULL DEFAULT false,
    needs_human_reason TEXT,
    autopilot        BOOLEAN NOT NULL DEFAULT true,
    deadline_at      TIMESTAMPTZ,
    quoted_at        TIMESTAMPTZ,
    quoted_total     NUMERIC(14, 2),
    quoted_currency  TEXT,
    quoted_total_eur NUMERIC(14, 2),
    booked           BOOLEAN NOT NULL DEFAULT false,
    llm_cost_usd     NUMERIC(12, 6) NOT NULL DEFAULT 0,
    event_count      INT NOT NULL DEFAULT 0,
    mail_count       INT NOT NULL DEFAULT 0,
    last_event_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_event_summary TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX session_list_idx ON session (last_event_at DESC, id DESC);
CREATE INDEX session_status_idx ON session (status, last_event_at DESC);
CREATE INDEX session_needs_human_idx ON session (last_event_at DESC) WHERE needs_human;

CREATE TABLE mail (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id     BIGINT NOT NULL UNIQUE,   -- id in the 5U mailbox
    direction       TEXT NOT NULL CHECK (direction IN ('in', 'out')),
    from_addr       TEXT NOT NULL,
    to_addr         TEXT NOT NULL,
    subject         TEXT NOT NULL DEFAULT '',
    body            TEXT NOT NULL DEFAULT '',
    in_reply_to_ext BIGINT,
    references_ext  BIGINT[] NOT NULL DEFAULT '{}',
    thread_root_ext BIGINT,                   -- first id of references, or own id
    session_id      BIGINT REFERENCES session (id),
    route_type      TEXT,
    route_confidence DOUBLE PRECISION,
    sent_at         TIMESTAMPTZ NOT NULL,     -- timestamp from the 5U API
    stored_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw             JSONB,
    search          TSVECTOR GENERATED ALWAYS AS (
                        to_tsvector('simple', coalesce(subject, '') || ' ' || coalesce(body, '') || ' ' || from_addr || ' ' || to_addr)
                    ) STORED
);
CREATE INDEX mail_session_idx ON mail (session_id, sent_at);
CREATE INDEX mail_thread_idx ON mail (thread_root_ext);
CREATE INDEX mail_in_reply_idx ON mail (in_reply_to_ext);
CREATE INDEX mail_list_idx ON mail (sent_at DESC, id DESC);
CREATE INDEX mail_search_idx ON mail USING GIN (search);

CREATE TABLE attachment (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mail_id      BIGINT NOT NULL REFERENCES mail (id),
    external_id  BIGINT,
    filename     TEXT NOT NULL,
    content_type TEXT,
    size         BIGINT,
    content      BYTEA
);
CREATE INDEX attachment_mail_idx ON attachment (mail_id);

CREATE TABLE event (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT REFERENCES session (id),
    mail_id    BIGINT REFERENCES mail (id),
    kind       TEXT NOT NULL,
    actor      TEXT NOT NULL CHECK (actor IN ('system', 'llm', 'human')),
    summary    TEXT NOT NULL,
    payload    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX event_session_idx ON event (session_id, id);
CREATE INDEX event_created_idx ON event (created_at DESC, id DESC);
CREATE INDEX event_kind_idx ON event (kind, created_at DESC);

CREATE TABLE llm_call (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id        BIGINT REFERENCES session (id),
    purpose           TEXT NOT NULL,
    model             TEXT NOT NULL,
    prompt_tokens     INT,
    completion_tokens INT,
    cost_usd          NUMERIC(12, 6),
    latency_ms        INT,
    success           BOOLEAN NOT NULL DEFAULT true,
    request           JSONB,
    response          JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX llm_call_session_idx ON llm_call (session_id, id);
CREATE INDEX llm_call_created_idx ON llm_call (created_at DESC);

-- RFQ workflow ----------------------------------------------------------------

CREATE TABLE rfq (
    session_id     BIGINT PRIMARY KEY REFERENCES session (id),
    origin_zip     TEXT,
    origin_airport TEXT,
    destination    TEXT,
    weight_kg      NUMERIC(12, 2),
    volume_cbm     NUMERIC(12, 3),
    chargeable_kg  NUMERIC(12, 2),
    pieces         INT,
    mode           TEXT,           -- cheapest | fastest
    door_pickup    BOOLEAN NOT NULL DEFAULT false,
    ready_date     DATE,
    commodity      TEXT,
    language       TEXT,           -- language the customer wrote in; replies use it
    customer_name  TEXT,
    extraction     JSONB
);

CREATE TABLE bid_request (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id   BIGINT NOT NULL REFERENCES session (id),
    carrier_id   BIGINT NOT NULL REFERENCES carrier (id),
    kind         TEXT NOT NULL CHECK (kind IN ('airline', 'trucker', 'ratedesk')),
    out_mail_id  BIGINT REFERENCES mail (id),
    in_mail_id   BIGINT REFERENCES mail (id),
    sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at  TIMESTAMPTZ,
    price        NUMERIC(14, 2),
    currency     TEXT,
    transit_days INT,
    valid_until  TIMESTAMPTZ,
    declined     BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX bid_request_session_idx ON bid_request (session_id);
CREATE INDEX bid_request_out_mail_idx ON bid_request (out_mail_id);

CREATE TABLE price_candidate (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id    BIGINT NOT NULL REFERENCES session (id),
    source        TEXT NOT NULL CHECK (source IN ('sheet', 'portal', 'bid', 'ratedesk', 'truck', 'combined')),
    airline_code  TEXT,
    airline_name  TEXT,
    total         NUMERIC(14, 2) NOT NULL,
    currency      TEXT NOT NULL,
    total_eur     NUMERIC(14, 2) NOT NULL,
    transit_days  INT,
    rate_id       TEXT,
    valid_until   TIMESTAMPTZ,
    breakdown     JSONB,
    chosen        BOOLEAN NOT NULL DEFAULT false,
    reject_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX price_candidate_session_idx ON price_candidate (session_id);

CREATE TABLE booking (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id        BIGINT NOT NULL REFERENCES session (id),
    rate_id           TEXT,
    team_reference    TEXT NOT NULL,
    booking_reference TEXT UNIQUE,
    airline_code      TEXT,
    airline_name      TEXT,
    origin            TEXT NOT NULL,
    destination       TEXT NOT NULL,
    price             NUMERIC(14, 2),
    currency          TEXT,
    transit_days      INT,
    status            TEXT NOT NULL CHECK (status IN ('requested', 'booked', 'confirmed', 'failed')),
    booked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at      TIMESTAMPTZ,
    departure_at      TIMESTAMPTZ,
    eta_at            TIMESTAMPTZ
);
CREATE INDEX booking_session_idx ON booking (session_id);
CREATE INDEX booking_status_idx ON booking (status);

-- Tariffs -----------------------------------------------------------------------

CREATE TABLE tariff (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    airline_name   TEXT NOT NULL,
    airline_code   TEXT,
    version        TEXT NOT NULL,
    source         TEXT NOT NULL CHECK (source IN ('kit', 'mail')),
    source_mail_id BIGINT REFERENCES mail (id),
    source_file    TEXT,
    valid_from     DATE,
    valid_to       DATE,
    currency       TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('active', 'pending_review', 'superseded', 'rejected')),
    notes          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tariff_airline_idx ON tariff (airline_name, status);

CREATE TABLE tariff_lane (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tariff_id    BIGINT NOT NULL REFERENCES tariff (id) ON DELETE CASCADE,
    origin       TEXT NOT NULL,
    destination  TEXT NOT NULL,
    transit_days INT,
    minimum      NUMERIC(12, 2) NOT NULL,
    breaks       JSONB NOT NULL,         -- [{"fromKg":0,"rate":5.92},{"fromKg":45,"rate":5.03},...]
    fuel_per_kg  NUMERIC(10, 4) NOT NULL DEFAULT 0,
    security_per_kg NUMERIC(10, 4) NOT NULL DEFAULT 0,
    extra        JSONB                   -- anything sheet-specific (notes, fixed fees, ...)
);
CREATE INDEX tariff_lane_lookup_idx ON tariff_lane (origin, destination);

-- Scoring feedback ------------------------------------------------------------------

CREATE TABLE board_result (
    request_key TEXT PRIMARY KEY,
    session_id  BIGINT REFERENCES session (id),
    round       TEXT,
    verdict     TEXT,
    reason_code TEXT,
    points      JSONB,
    raw         JSONB,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Pending outbound mail waiting for a human (needs_human or autopilot off) ------------
CREATE TABLE draft (
    session_id      BIGINT PRIMARY KEY REFERENCES session (id),
    purpose         TEXT NOT NULL,      -- quote | booking_confirmation | tracking_answer | other
    to_addr         TEXT NOT NULL,
    subject         TEXT NOT NULL,
    body            TEXT NOT NULL,
    in_reply_to_ext BIGINT,
    meta            JSONB,              -- e.g. chosen candidate id, totals for claimQuote
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
