-- Carrier directory and per-RFQ carrier bids mirrored from the worker's SQLite ledger.
-- The backend writes with the project's secret key, which bypasses RLS. RLS stays on with no
-- policies so the publishable key cannot read or write these tables.

create table if not exists public.carriers (
  email text primary key,
  name text not null,
  kind text not null check (kind in ('airline', 'trucker')),
  serves text[] not null default '{}',
  updated_at timestamptz not null default now()
);

create table if not exists public.carrier_bids (
  request_id bigint not null,
  carrier_email text not null references public.carriers (email),
  leg text,
  status text not null,
  rfq_subject text,
  rfq_received_at timestamptz,
  origin text,
  destination text,
  weight_kg numeric,
  volume_cbm numeric,
  preference text,
  pickup_zone text,
  invite_action text,
  invite_state text,
  invite_message_id bigint,
  invited_at timestamptz,
  reply_id bigint,
  replied_at timestamptz,
  price_eur numeric,
  transit_days numeric,
  issue text,
  terms jsonb,
  updated_at timestamptz not null default now(),
  primary key (request_id, carrier_email)
);

create index if not exists carrier_bids_carrier_email_idx on public.carrier_bids (carrier_email);

create or replace function public.touch_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists carriers_touch on public.carriers;
create trigger carriers_touch before update on public.carriers
  for each row execute function public.touch_updated_at();

drop trigger if exists carrier_bids_touch on public.carrier_bids;
create trigger carrier_bids_touch before update on public.carrier_bids
  for each row execute function public.touch_updated_at();

alter table public.carriers enable row level security;
alter table public.carrier_bids enable row level security;
