-- Postgres mirror of the worker's SQLite ledger (backend/state/dispatch.sqlite3).
-- Same tables, columns and keys as Ledger.java so rows copy 1:1.
-- JSON text columns become jsonb, ISO timestamps become timestamptz, money becomes numeric.
-- Epoch columns stay bigint (seconds, except portal_rate_history.seen which is milliseconds).

create table if not exists cursor (
  id integer primary key check (id = 1),
  value bigint not null
);

create table if not exists mail (
  id bigint primary key,
  root bigint not null,
  sender text,
  subject text,
  body text,
  received timestamptz,
  kind text,
  fields jsonb,
  attachments jsonb,
  stage text not null default 'review',
  proposal jsonb,
  booking jsonb
);
create index if not exists mail_root_idx on mail (root);

create table if not exists deliveries (
  action text primary key,
  state text not null,
  response jsonb,
  payload jsonb,
  attempted_at timestamptz
);

create table if not exists wave_case (
  id bigint primary key,
  reason text,
  offers jsonb,
  queried bigint default 0,
  opened bigint not null,
  operator bigint not null default 0,
  customer_reply bigint
);

create table if not exists tender (
  request_id bigint,
  email text,
  leg text,
  reply_id bigint,
  euros numeric,
  days numeric,
  issue text,
  terms jsonb,
  bid_action text,
  primary key (request_id, email)
);

create table if not exists retry (
  action text primary key,
  after_epoch bigint not null
);

create table if not exists rfq_parse (
  request_id bigint primary key,
  state text not null,
  model text not null,
  result jsonb
);

create table if not exists booking_attempt (
  request_id bigint primary key,
  rate_id text not null,
  retry_at bigint default 0,
  issue text
);

create table if not exists document_check (
  request_id bigint primary key,
  checked_through bigint not null
);

create table if not exists board_result (
  received timestamptz primary key,
  result jsonb not null
);

create table if not exists truck_bid_parse (
  reply_id bigint primary key,
  state text not null,
  result jsonb,
  verified_days double precision
);

create table if not exists dg_gateway_check (
  request_id bigint primary key,
  state text not null,
  result jsonb
);

create table if not exists portal_rate_history (
  origin text not null,
  destination text not null,
  airline text not null,
  bracket text not null,
  total numeric not null,
  kg numeric not null,
  days double precision not null,
  seen bigint not null,
  primary key (origin, destination, airline, bracket)
);

create table if not exists mailbox_push (
  id bigint primary key,
  payload jsonb not null
);
