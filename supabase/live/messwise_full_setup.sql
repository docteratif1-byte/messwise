-- MessWise full database setup (schema, engine, API, storage and access rules). Run once in the SQL editor of a new Supabase project.

-- ================= supabase/migrations/0001_schema.sql
-- Messwise schema. Lives in the private "messwise" schema inside the Comitto Supabase project,
-- fully separate from Comitto's "app" schema. The public API does not expose these tables:
-- phones reach data only through the checked public.mw_* functions.
-- Money is stored in integer paisa (Rs 1 = 100 paisa).

create schema if not exists messwise;
revoke all on schema messwise from public;

-- ---------------------------------------------------------------- accounts and settings
create table messwise.profiles (
  user_id     uuid primary key,
  full_name   text not null default '',
  email       text,
  language    text not null default 'en' check (language in ('en','ur')),
  budget_paisa bigint check (budget_paisa is null or budget_paisa between 0 and 100000000000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table messwise.settings (
  key        text primary key,
  value      jsonb not null,
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------- offices and hostels
create table messwise.places (
  id                 uuid primary key default gen_random_uuid(),
  name               text not null check (length(trim(name)) between 2 and 80),
  address            text not null default '' check (length(address) <= 160),
  kind               text not null default 'hostel' check (kind in ('hostel','office','flat','other')),
  logo_path          text,
  join_code          text not null unique,
  round_to_rupee     boolean not null default true,
  party_office_split text not null default 'all' check (party_office_split in ('all','attendees')),
  owner_user_id      uuid not null,
  created_at         timestamptz not null default now(),
  archived_at        timestamptz
);

-- role: owner (one per place), manager (Canteen Manager or deputy), member.
-- on_tea: on the Tea Bill. on_kitchen: shares the kitchen (pays shared kitchen costs). Meals are open to everyone.
create table messwise.members (
  id              uuid primary key default gen_random_uuid(),
  place_id        uuid not null references messwise.places(id),
  user_id         uuid,
  display_name    text not null check (length(trim(display_name)) between 1 and 60),
  role            text not null default 'member' check (role in ('owner','manager','member')),
  is_main_manager boolean not null default false,
  on_tea          boolean not null default true,
  on_kitchen      boolean not null default true,
  status          text not null default 'active' check (status in ('active','left')),
  joined_on       date not null,
  left_on         date,
  join_order      int not null,
  created_at      timestamptz not null default now(),
  check (on_tea or on_kitchen),
  check (left_on is null or left_on >= joined_on)
);
create unique index members_user_uq on messwise.members(place_id, user_id) where user_id is not null and status = 'active';
create unique index members_main_mgr_uq on messwise.members(place_id) where is_main_manager;
create unique index members_owner_uq on messwise.members(place_id) where role = 'owner';
create index members_user_idx on messwise.members(user_id);
create index members_place_idx on messwise.members(place_id);

create table messwise.join_requests (
  id         uuid primary key default gen_random_uuid(),
  place_id   uuid not null references messwise.places(id),
  user_id    uuid not null,
  full_name  text not null,
  status     text not null default 'pending' check (status in ('pending','approved','declined')),
  member_id  uuid,
  decided_by uuid,
  decided_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index join_requests_pending_uq on messwise.join_requests(place_id, user_id) where status = 'pending';

-- A leave: from_date to to_date (both days on leave). to_date null = until back.
create table messwise.leaves (
  id         uuid primary key default gen_random_uuid(),
  member_id  uuid not null references messwise.members(id),
  from_date  date not null,
  to_date    date,
  note       text not null default '' check (length(note) <= 200),
  status     text not null default 'active' check (status in ('active','void')),
  created_by uuid not null,
  created_at timestamptz not null default now(),
  check (to_date is null or to_date >= from_date)
);
create index leaves_member_idx on messwise.leaves(member_id);

-- ---------------------------------------------------------------- months
create table messwise.months (
  place_id      uuid not null references messwise.places(id),
  month         date not null check (extract(day from month) = 1),
  status        text not null default 'billed' check (status in ('open','billed')),
  summary       jsonb not null default '{}'::jsonb,
  billed_by     uuid,
  billed_at     timestamptz,
  reopened_by   uuid,
  reopened_at   timestamptz,
  reopen_reason text,
  primary key (place_id, month)
);

-- ---------------------------------------------------------------- entries
create table messwise.purchases (
  id           uuid primary key default gen_random_uuid(),
  place_id     uuid not null references messwise.places(id),
  bill         text not null check (bill in ('tea','kitchen')),
  item         text not null check (length(trim(item)) between 1 and 80),
  amount_paisa bigint not null check (amount_paisa > 0 and amount_paisa <= 100000000000),
  bought_on    date not null,
  note         text not null default '' check (length(note) <= 300),
  receipt_path text,
  status       text not null default 'active' check (status in ('active','void')),
  void_reason  text,
  version      int not null default 1,
  created_by   uuid not null,
  created_at   timestamptz not null default now(),
  updated_by   uuid,
  updated_at   timestamptz
);
create index purchases_place_day_idx on messwise.purchases(place_id, bought_on);

create table messwise.meals (
  id           uuid primary key default gen_random_uuid(),
  place_id     uuid not null references messwise.places(id),
  day          date not null,
  kind         text not null check (kind in ('breakfast','lunch','dinner','other')),
  title        text not null default '' check (length(title) <= 80),
  total_paisa  bigint not null check (total_paisa > 0 and total_paisa <= 100000000000),
  note         text not null default '' check (length(note) <= 300),
  receipt_path text,
  status       text not null default 'active' check (status in ('active','void')),
  void_reason  text,
  version      int not null default 1,
  created_by   uuid not null,
  created_at   timestamptz not null default now(),
  updated_by   uuid,
  updated_at   timestamptz
);
create index meals_place_day_idx on messwise.meals(place_id, day);

-- subject: member (member_id = the member), personal_guest (member_id = host), office_guest (member_id null).
create table messwise.meal_people (
  meal_id     uuid not null references messwise.meals(id),
  subject     text not null check (subject in ('member','personal_guest','office_guest')),
  member_id   uuid references messwise.members(id),
  units       numeric(8,2) not null default 1 check (units > 0 and units <= 500),
  exact_paisa bigint check (exact_paisa is null or exact_paisa >= 0),
  check ((subject = 'office_guest') = (member_id is null))
);
create index meal_people_meal_idx on messwise.meal_people(meal_id);

create table messwise.parties (
  id           uuid primary key default gen_random_uuid(),
  place_id     uuid not null references messwise.places(id),
  day          date not null,
  title        text not null check (length(trim(title)) between 1 and 80),
  total_paisa  bigint not null check (total_paisa > 0 and total_paisa <= 100000000000),
  office_split text not null default 'all' check (office_split in ('all','attendees')),
  add_to_month boolean not null default false,
  note         text not null default '' check (length(note) <= 300),
  receipt_path text,
  status       text not null default 'active' check (status in ('active','void')),
  void_reason  text,
  version      int not null default 1,
  created_by   uuid not null,
  created_at   timestamptz not null default now(),
  updated_by   uuid,
  updated_at   timestamptz
);
create index parties_place_day_idx on messwise.parties(place_id, day);

create table messwise.party_people (
  party_id  uuid not null references messwise.parties(id),
  subject   text not null check (subject in ('member','personal_guest','office_guest')),
  member_id uuid references messwise.members(id),
  heads     int not null default 1 check (heads between 1 and 500),
  check ((subject = 'office_guest') = (member_id is null))
);
create index party_people_party_idx on messwise.party_people(party_id);

-- Who paid an entry, and how much (adds up to the entry total).
create table messwise.entry_payers (
  entry_type   text not null check (entry_type in ('purchase','meal','party')),
  entry_id     uuid not null,
  member_id    uuid not null references messwise.members(id),
  amount_paisa bigint not null check (amount_paisa > 0),
  primary key (entry_type, entry_id, member_id)
);

-- Each member's final share of a meal or party (guests already folded into hosts and everyone).
create table messwise.entry_shares (
  entry_type  text not null check (entry_type in ('meal','party')),
  entry_id    uuid not null,
  member_id   uuid not null references messwise.members(id),
  share_paisa bigint not null check (share_paisa >= 0),
  detail      jsonb not null default '{}'::jsonb,
  primary key (entry_type, entry_id, member_id)
);

create table messwise.entry_versions (
  id         bigserial primary key,
  entry_type text not null,
  entry_id   uuid not null,
  version    int not null,
  snapshot   jsonb not null,
  reason     text not null,
  changed_by uuid not null,
  changed_at timestamptz not null default now()
);
create index entry_versions_entry_idx on messwise.entry_versions(entry_type, entry_id);

-- Official tea count kept by the Canteen Manager. subject as in meal_people.
create table messwise.tea_counts (
  place_id   uuid not null references messwise.places(id),
  day        date not null,
  subject    text not null check (subject in ('member','personal_guest','office_guest')),
  member_id  uuid references messwise.members(id),
  cups       int not null check (cups between 1 and 300),
  updated_by uuid not null,
  updated_at timestamptz not null default now(),
  check ((subject = 'office_guest') = (member_id is null))
);
create unique index tea_counts_uq on messwise.tea_counts(place_id, day, subject, coalesce(member_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Each person's own private tea tally (Add Tea button). Only its owner can read it.
create table messwise.my_tea_marks (
  id        bigserial primary key,
  user_id   uuid not null,
  place_id  uuid not null references messwise.places(id),
  day       date not null,
  cups      int not null default 1 check (cups between 1 and 5),
  marked_at timestamptz not null default now()
);
create index my_tea_marks_user_idx on messwise.my_tea_marks(user_id, place_id, day);

-- ---------------------------------------------------------------- bills and payments
-- kind month: Tea + Kitchen (+ parties added to the month) for one member and month.
-- kind party: one member's share of a party collected on its own.
-- total_paisa = charges - credit (what the member paid for the group); below zero = gets back.
create table messwise.bills (
  id                   uuid primary key default gen_random_uuid(),
  place_id             uuid not null references messwise.places(id),
  member_id            uuid not null references messwise.members(id),
  kind                 text not null check (kind in ('month','party')),
  month                date not null,
  party_id             uuid references messwise.parties(id),
  tea_paisa            bigint not null default 0,
  kitchen_shared_paisa bigint not null default 0,
  meals_paisa          bigint not null default 0,
  party_paisa          bigint not null default 0,
  credit_paisa         bigint not null default 0,
  total_paisa          bigint not null,
  detail               jsonb not null default '{}'::jsonb,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  check ((kind = 'party') = (party_id is not null))
);
create unique index bills_month_uq on messwise.bills(member_id, month) where kind = 'month';
create unique index bills_party_uq on messwise.bills(member_id, party_id) where kind = 'party';
create index bills_place_idx on messwise.bills(place_id);

-- direction in: member pays the manager. out: manager pays a member back.
-- bill_id null + direction in = an advance, attached to the member's next month bill.
create table messwise.payments (
  id           uuid primary key default gen_random_uuid(),
  place_id     uuid not null references messwise.places(id),
  bill_id      uuid references messwise.bills(id),
  member_id    uuid not null references messwise.members(id),
  direction    text not null default 'in' check (direction in ('in','out')),
  amount_paisa bigint not null check (amount_paisa > 0 and amount_paisa <= 100000000000),
  method       text not null check (method in ('cash','jazzcash','easypaisa','bank','other')),
  slip_path    text,
  note         text not null default '' check (length(note) <= 300),
  status       text not null check (status in ('sent','accepted','sent_back')),
  reason       text,
  sent_by      uuid not null,
  created_at   timestamptz not null default now(),
  decided_by   uuid,
  decided_at   timestamptz
);
create index payments_bill_idx on messwise.payments(bill_id);
create index payments_place_status_idx on messwise.payments(place_id, status);

create table messwise.reminders (
  id         bigserial primary key,
  place_id   uuid not null references messwise.places(id),
  from_user  uuid not null,
  to_member  uuid not null references messwise.members(id),
  note       text not null default '',
  sent_on    date not null,
  created_at timestamptz not null default now()
);
create unique index reminders_daily_uq on messwise.reminders(to_member, sent_on);

-- ---------------------------------------------------------------- questions, notifications, history
create table messwise.questions (
  id          uuid primary key default gen_random_uuid(),
  place_id    uuid not null references messwise.places(id),
  member_id   uuid not null references messwise.members(id),
  entity      text not null check (entity in ('purchase','meal','party','tea','bill','other')),
  entity_id   uuid,
  month       date,
  text        text not null check (length(trim(text)) between 2 and 500),
  tally       jsonb,
  status      text not null default 'open' check (status in ('open','answered')),
  answer      text,
  answered_by uuid,
  answered_at timestamptz,
  created_at  timestamptz not null default now()
);
create index questions_place_idx on messwise.questions(place_id, status);

create table messwise.notifications (
  id         bigserial primary key,
  user_id    uuid not null,
  place_id   uuid,
  kind       text not null,
  title      text not null,
  body       text not null default '',
  data       jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  read_at    timestamptz
);
create index notifications_user_idx on messwise.notifications(user_id, id desc);

create table messwise.activity (
  id         bigserial primary key,
  place_id   uuid not null references messwise.places(id),
  actor      uuid,
  actor_name text not null default '',
  action     text not null,
  entity     text not null,
  entity_id  uuid,
  summary    text not null default '',
  at         timestamptz not null default now()
);
create index activity_place_idx on messwise.activity(place_id, id desc);

-- ---------------------------------------------------------------- private expense book
create table messwise.personal_expenses (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null,
  amount_paisa bigint not null check (amount_paisa > 0 and amount_paisa <= 100000000000),
  category     text not null check (length(trim(category)) between 1 and 40),
  note         text not null default '' check (length(note) <= 300),
  spent_on     date not null,
  receipt_path text,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz
);
create index personal_expenses_user_idx on messwise.personal_expenses(user_id, spent_on);

-- Nothing in this schema is reachable directly; row-level security with no policies blocks it too.
do $$
declare t record;
begin
  for t in select tablename from pg_tables where schemaname = 'messwise' loop
    execute format('alter table messwise.%I enable row level security', t.tablename);
  end loop;
end $$;

-- ================= supabase/migrations/0002_engine.sql
-- Helpers and the bill engine. None of these are callable by app users directly.

create or replace function messwise.err(code text, msg text) returns void language plpgsql as $$
begin
  raise exception 'MESSWISE_%: %', code, msg using errcode = 'P0001';
end $$;

create or replace function messwise.uid() returns uuid language plpgsql stable as $$
declare u uuid := auth.uid();
begin
  if u is null then perform messwise.err('NOT_AUTH', 'sign in required'); end if;
  return u;
end $$;

-- Pakistan date. Tests pin it with: set messwise.test_today = '2026-11-30'.
create or replace function messwise.today() returns date language sql stable as $$
  select coalesce(nullif(current_setting('messwise.test_today', true), '')::date,
                  (now() at time zone 'Asia/Karachi')::date)
$$;

create or replace function messwise.month_of(d date) returns date language sql immutable as $$
  select date_trunc('month', d)::date
$$;

create or replace function messwise.month_end(m date) returns date language sql immutable as $$
  select (date_trunc('month', m) + interval '1 month - 1 day')::date
$$;

-- Readable random code without look-alike characters.
create or replace function messwise.gen_code(len int) returns text language plpgsql volatile as $$
declare
  alphabet text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  b bytea := extensions.gen_random_bytes(len);
  out text := '';
begin
  for i in 0 .. len - 1 loop
    out := out || substr(alphabet, (get_byte(b, i) % length(alphabet)) + 1, 1);
  end loop;
  return out;
end $$;

create or replace function messwise.ensure_profile() returns messwise.profiles language plpgsql as $$
declare
  u uuid := messwise.uid();
  p messwise.profiles;
  au record;
begin
  select * into p from messwise.profiles where user_id = u;
  if found then return p; end if;
  select email, coalesce(raw_user_meta_data ->> 'full_name', raw_user_meta_data ->> 'name', '') as name
    into au from auth.users where id = u;
  insert into messwise.profiles(user_id, full_name, email) values (u, coalesce(au.name, ''), au.email)
    on conflict (user_id) do nothing;
  select * into p from messwise.profiles where user_id = u;
  return p;
end $$;

create or replace function messwise.place(p_id uuid) returns messwise.places language plpgsql stable as $$
declare pl messwise.places;
begin
  select * into pl from messwise.places where id = p_id and archived_at is null;
  if not found then perform messwise.err('NOT_FOUND', 'office or hostel not found'); end if;
  return pl;
end $$;

-- The caller's active membership in a place, or NOT_FOUND (others' places look like they do not exist).
create or replace function messwise.me_in(p_place uuid) returns messwise.members language plpgsql stable as $$
declare m messwise.members;
begin
  perform messwise.place(p_place);
  select * into m from messwise.members where place_id = p_place and user_id = messwise.uid() and status = 'active';
  if not found then perform messwise.err('NOT_FOUND', 'office or hostel not found'); end if;
  return m;
end $$;

create or replace function messwise.is_admin(m messwise.members) returns boolean language sql immutable as $$
  select m.role in ('owner','manager') and m.status = 'active'
$$;

create or replace function messwise.require_admin(p_place uuid) returns messwise.members language plpgsql stable as $$
declare m messwise.members := messwise.me_in(p_place);
begin
  if not messwise.is_admin(m) then perform messwise.err('FORBIDDEN', 'only the owner or the Canteen Manager can do this'); end if;
  return m;
end $$;

create or replace function messwise.require_owner(p_place uuid) returns messwise.members language plpgsql stable as $$
declare m messwise.members := messwise.me_in(p_place);
begin
  if m.role <> 'owner' then perform messwise.err('FORBIDDEN', 'only the owner can do this'); end if;
  return m;
end $$;

create or replace function messwise.member_of(p_place uuid, p_member uuid) returns messwise.members language plpgsql stable as $$
declare m messwise.members;
begin
  select * into m from messwise.members where id = p_member and place_id = p_place;
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  return m;
end $$;

create or replace function messwise.month_billed(p_place uuid, p_day date) returns boolean language sql stable as $$
  select exists (select 1 from messwise.months where place_id = p_place and month = messwise.month_of(p_day) and status = 'billed')
$$;

create or replace function messwise.require_open(p_place uuid, p_day date) returns void language plpgsql stable as $$
begin
  if p_day is null then perform messwise.err('INVALID', 'date is required'); end if;
  if p_day > messwise.today() + 1 then perform messwise.err('INVALID', 'date cannot be in the future'); end if;
  if messwise.month_billed(p_place, p_day) then
    perform messwise.err('STATE', 'bills for ' || to_char(p_day, 'Mon YYYY') || ' are already made; the owner must reopen the month first');
  end if;
end $$;

create or replace function messwise.log(p_place uuid, p_action text, p_entity text, p_entity_id uuid, p_summary text) returns void language plpgsql as $$
declare n text;
begin
  select display_name into n from messwise.members where place_id = p_place and user_id = auth.uid() order by status = 'active' desc limit 1;
  insert into messwise.activity(place_id, actor, actor_name, action, entity, entity_id, summary)
  values (p_place, auth.uid(), coalesce(n, ''), p_action, p_entity, p_entity_id, coalesce(p_summary, ''));
end $$;

-- In-app notifications. p_to: 'all', 'admins', or a member id. The person acting is left out unless p_self.
create or replace function messwise.notify(p_place uuid, p_to text, p_kind text, p_title text, p_body text, p_data jsonb default '{}'::jsonb, p_self boolean default false)
returns void language plpgsql as $$
begin
  insert into messwise.notifications(user_id, place_id, kind, title, body, data)
  select distinct m.user_id, p_place, p_kind, p_title, coalesce(p_body, ''), coalesce(p_data, '{}'::jsonb) || jsonb_build_object('place_id', p_place)
  from messwise.members m
  where m.place_id = p_place and m.user_id is not null and m.status = 'active'
    and (p_self or m.user_id is distinct from auth.uid())
    and (case when p_to = 'all' then true
              when p_to = 'admins' then m.role in ('owner','manager')
              else m.id::text = p_to end);
end $$;

create or replace function messwise.rs(paisa bigint) returns text language sql immutable as $$
  select 'Rs ' || to_char(paisa / 100, 'FM999,999,999,990') ||
         case when paisa % 100 <> 0 then '.' || lpad(abs(paisa % 100)::text, 2, '0') else '' end
$$;

-- ---------------------------------------------------------------- presence

create or replace function messwise.on_leave(p_member uuid, d date) returns boolean language sql stable as $$
  select exists (select 1 from messwise.leaves l
                  where l.member_id = p_member and l.status = 'active'
                    and l.from_date <= d and (l.to_date is null or d <= l.to_date))
$$;

-- Member is part of the place on day d (joined, not left).
create or replace function messwise.belongs(m messwise.members, d date) returns boolean language sql immutable as $$
  select m.joined_on <= d and (m.left_on is null or d <= m.left_on)
$$;

-- Days in [p_from, p_to] the member belonged to the place and was not on leave.
create or replace function messwise.days_present(p_member uuid, p_from date, p_to date) returns int language sql stable as $$
  select count(*)::int
  from messwise.members m, generate_series(p_from, p_to, interval '1 day') g(d)
  where m.id = p_member and messwise.belongs(m, g.d::date) and not messwise.on_leave(m.id, g.d::date)
$$;

-- Members present on day d (belong and not on leave).
create or replace function messwise.present_ids(p_place uuid, d date) returns uuid[] language sql stable as $$
  select coalesce(array_agg(m.id order by m.join_order), '{}')
  from messwise.members m
  where m.place_id = p_place and messwise.belongs(m, d) and not messwise.on_leave(m.id, d)
$$;

-- ---------------------------------------------------------------- dividing money exactly
-- Splits p_total in proportion to weights. p_items: [{"k": key, "w": weight, "o": order}].
-- Works in whole rupees when p_round and the total is whole rupees, else in paisa. Leftover units go
-- one each to the largest fractions (ties by order), so the parts always add up exactly to p_total.
create or replace function messwise.alloc(p_total bigint, p_items jsonb, p_round boolean default true)
returns jsonb language plpgsql stable as $$
declare
  unit bigint := case when p_round and p_total % 100 = 0 then 100 else 1 end;
  units bigint := p_total / unit;
  w_sum numeric;
  out jsonb;
begin
  if p_items is null or jsonb_array_length(p_items) = 0 then
    if p_total <> 0 then perform messwise.err('STATE', 'nobody to share this amount with'); end if;
    return '{}'::jsonb;
  end if;
  select sum((i ->> 'w')::numeric) into w_sum from jsonb_array_elements(p_items) i;
  if p_total = 0 then
    select jsonb_object_agg(i ->> 'k', 0) into out from jsonb_array_elements(p_items) i;
    return out;
  end if;
  if w_sum is null or w_sum <= 0 then perform messwise.err('STATE', 'nobody to share this amount with'); end if;
  with it as (
    select i ->> 'k' as k, (i ->> 'w')::numeric as w, coalesce((i ->> 'o')::int, 0) as o, t.ord
    from jsonb_array_elements(p_items) with ordinality as t(i, ord)
  ), b as (
    select k, o, ord, floor(units * w / w_sum)::bigint as base, (units * w / w_sum) - floor(units * w / w_sum) as frac from it
  ), ranked as (
    select k, base, row_number() over (order by frac desc, o, ord) as rn from b
  )
  select jsonb_object_agg(k, (base + case when rn <= units - (select sum(base) from b) then 1 else 0 end) * unit)
    into out from ranked;
  return out;
end $$;

-- ---------------------------------------------------------------- meal and party shares

-- Works out each member's share of a meal: exact amounts first, the rest by units; a personal guest's
-- part goes to the host; an office guest's part is divided equally on everyone present that day.
create or replace function messwise.meal_shares(p_meal uuid) returns jsonb language plpgsql stable as $$
declare
  ml messwise.meals;
  pl messwise.places;
  present uuid[];
  fixed jsonb := '{}'::jsonb;
  office_fixed bigint := 0;
  exact_sum bigint;
  rest bigint;
  n_present int;
  office_units numeric;
  items jsonb;
  a jsonb;
  b jsonb := '{}'::jsonb;
  total jsonb := '{}'::jsonb;
  r record;
begin
  select * into ml from messwise.meals where id = p_meal;
  select * into pl from messwise.places where id = ml.place_id;
  present := messwise.present_ids(ml.place_id, ml.day);
  n_present := coalesce(array_length(present, 1), 0);

  select coalesce(sum(exact_paisa), 0) into exact_sum from messwise.meal_people where meal_id = p_meal and exact_paisa is not null;
  rest := ml.total_paisa - exact_sum;
  if rest < 0 then perform messwise.err('INVALID', 'the fixed amounts are more than the bill'); end if;

  for r in select member_id, sum(exact_paisa) s from messwise.meal_people
           where meal_id = p_meal and exact_paisa is not null and subject <> 'office_guest' group by member_id loop
    fixed := fixed || jsonb_build_object(r.member_id::text, r.s);
  end loop;
  select coalesce(sum(exact_paisa), 0) into office_fixed from messwise.meal_people where meal_id = p_meal and exact_paisa is not null and subject = 'office_guest';
  select coalesce(sum(units), 0) into office_units from messwise.meal_people where meal_id = p_meal and exact_paisa is null and subject = 'office_guest';

  if (office_units > 0 or office_fixed > 0) and n_present = 0 then
    perform messwise.err('STATE', 'an office guest''s share needs at least one member present that day');
  end if;

  -- Weights for the remainder: own and personal-guest units times the number present, plus office units for those present.
  if rest > 0 then
    select jsonb_agg(jsonb_build_object('k', k, 'w', w, 'o', o)) into items from (
      select x.member_id::text k, sum(x.w) w, min(x.o) o from (
        select mp.member_id, mp.units * greatest(n_present, 1) as w, m.join_order as o
          from messwise.meal_people mp join messwise.members m on m.id = mp.member_id
         where mp.meal_id = p_meal and mp.exact_paisa is null and mp.subject <> 'office_guest'
        union all
        select m.id, office_units, m.join_order from messwise.members m where m.id = any(present) and office_units > 0
      ) x group by x.member_id
    ) y;
    if items is null then perform messwise.err('INVALID', 'tick who ate, or give the amounts'); end if;
    a := messwise.alloc(rest, items, pl.round_to_rupee);
  else
    a := '{}'::jsonb;
  end if;

  if office_fixed > 0 then
    select jsonb_agg(jsonb_build_object('k', m.id::text, 'w', 1, 'o', m.join_order)) into items
      from messwise.members m where m.id = any(present);
    b := messwise.alloc(office_fixed, items, pl.round_to_rupee);
  end if;

  select coalesce(jsonb_object_agg(k, v), '{}'::jsonb) into total from (
    select k, sum(v::bigint) v from (
      select key k, value::text v from jsonb_each_text(fixed)
      union all select key, value::text from jsonb_each_text(a)
      union all select key, value::text from jsonb_each_text(b)
    ) s group by k
  ) t;
  return total;
end $$;

-- Party: share per head = total / everyone who attended. Personal guests go to their host; office
-- guests are divided on all members present that day (office_split 'all') or only on attendees.
create or replace function messwise.party_shares(p_party uuid) returns jsonb language plpgsql stable as $$
declare
  pt messwise.parties;
  pl messwise.places;
  split_set uuid[];
  n_split int;
  office_heads int;
  items jsonb;
begin
  select * into pt from messwise.parties where id = p_party;
  select * into pl from messwise.places where id = pt.place_id;
  select coalesce(sum(heads), 0) into office_heads from messwise.party_people where party_id = p_party and subject = 'office_guest';
  if pt.office_split = 'attendees' then
    select coalesce(array_agg(distinct member_id), '{}') into split_set from messwise.party_people where party_id = p_party and subject = 'member';
  else
    -- Everyone present that day, plus attending members even if marked away.
    split_set := array(select distinct x from unnest(
      messwise.present_ids(pt.place_id, pt.day) ||
      coalesce((select array_agg(member_id) from messwise.party_people where party_id = p_party and subject = 'member'), '{}'::uuid[])) x);
  end if;
  n_split := coalesce(array_length(split_set, 1), 0);
  if office_heads > 0 and n_split = 0 then perform messwise.err('STATE', 'office guests need at least one member to share their part'); end if;

  select jsonb_agg(jsonb_build_object('k', k, 'w', w, 'o', o)) into items from (
    select x.member_id::text k, sum(x.w) w, min(x.o) o from (
      select pp.member_id, (pp.heads * greatest(n_split, 1))::numeric as w, m.join_order as o
        from messwise.party_people pp join messwise.members m on m.id = pp.member_id
       where pp.party_id = p_party and pp.subject <> 'office_guest'
      union all
      select m.id, office_heads::numeric, m.join_order from messwise.members m where m.id = any(split_set) and office_heads > 0
    ) x group by x.member_id
  ) y;
  if items is null then perform messwise.err('INVALID', 'add who attended'); end if;
  return messwise.alloc(pt.total_paisa, items, pl.round_to_rupee);
end $$;

-- ---------------------------------------------------------------- the month

-- Everything about one month for one place. p_upto: last day counted for days present (today for a
-- live view, month end for the final bill). p_strict: raise instead of warning when a bill cannot be made.
create or replace function messwise.month_calc(p_place uuid, p_month date, p_upto date, p_strict boolean default false)
returns jsonb language plpgsql stable as $$
declare
  pl messwise.places := messwise.place(p_place);
  m0 date := messwise.month_of(p_month);
  m1 date := messwise.month_end(p_month);
  upto date := least(greatest(p_upto, m0), m1);
  tea_total bigint; kitchen_total bigint; meals_total bigint; parties_total bigint;
  cups_members int; cups_personal int; cups_office int; cups_total int;
  tea_set uuid[]; n_tea int;
  tea_items jsonb; tea_a jsonb := '{}'::jsonb;
  days jsonb := '{}'::jsonb; member_days int := 0;
  k_items jsonb; k_a jsonb := '{}'::jsonb;
  warnings jsonb := '[]'::jsonb;
  rows_ jsonb;
  r record;
begin
  select coalesce(sum(amount_paisa), 0) into tea_total from messwise.purchases
   where place_id = p_place and bill = 'tea' and status = 'active' and bought_on between m0 and m1;
  select coalesce(sum(amount_paisa), 0) into kitchen_total from messwise.purchases
   where place_id = p_place and bill = 'kitchen' and status = 'active' and bought_on between m0 and m1;
  select coalesce(sum(total_paisa), 0) into meals_total from messwise.meals
   where place_id = p_place and status = 'active' and day between m0 and m1;
  select coalesce(sum(total_paisa), 0) into parties_total from messwise.parties
   where place_id = p_place and status = 'active' and add_to_month and day between m0 and m1;

  select coalesce(sum(cups) filter (where subject = 'member'), 0),
         coalesce(sum(cups) filter (where subject = 'personal_guest'), 0),
         coalesce(sum(cups) filter (where subject = 'office_guest'), 0)
    into cups_members, cups_personal, cups_office
    from messwise.tea_counts where place_id = p_place and day between m0 and m1;
  cups_total := cups_members + cups_personal + cups_office;

  -- Tea members for the office guests' share: on the Tea Bill and part of the place during the month.
  select coalesce(array_agg(id order by join_order), '{}') into tea_set from messwise.members
   where place_id = p_place and on_tea and joined_on <= m1 and (left_on is null or left_on >= m0);
  n_tea := coalesce(array_length(tea_set, 1), 0);

  if tea_total > 0 and cups_total = 0 then
    if p_strict then perform messwise.err('STATE', 'tea was bought but no teas are counted for this month'); end if;
    warnings := warnings || to_jsonb('no_tea_counted'::text);
  elsif cups_office > 0 and n_tea = 0 then
    if p_strict then perform messwise.err('STATE', 'office guests had tea but nobody is on the Tea Bill'); end if;
    warnings := warnings || to_jsonb('no_tea_members'::text);
  elsif tea_total > 0 then
    -- Each person: (own cups + personal guests' cups) x rate + office guests' cups x rate / tea members.
    -- As whole-number weights: (own + guests) x N + office for tea members.
    select jsonb_agg(jsonb_build_object('k', k, 'w', w, 'o', o)) into tea_items from (
      select x.member_id::text k, sum(x.w) w, min(x.o) o from (
        select t.member_id, (t.cups * greatest(n_tea, 1))::numeric w, m.join_order o
          from messwise.tea_counts t join messwise.members m on m.id = t.member_id
         where t.place_id = p_place and t.day between m0 and m1 and t.subject <> 'office_guest'
        union all
        select m.id, cups_office::numeric, m.join_order from messwise.members m where m.id = any(tea_set) and cups_office > 0
      ) x group by x.member_id
    ) y;
    tea_a := messwise.alloc(tea_total, tea_items, pl.round_to_rupee);
  end if;

  -- Shared kitchen costs by days present, for kitchen members.
  for r in select id, join_order from messwise.members where place_id = p_place and on_kitchen
             and joined_on <= upto and (left_on is null or left_on >= m0) loop
    days := days || jsonb_build_object(r.id::text, messwise.days_present(r.id, m0, upto));
  end loop;
  select coalesce(sum(value::int), 0) into member_days from jsonb_each_text(days);
  if kitchen_total > 0 and member_days = 0 then
    if p_strict then perform messwise.err('STATE', 'kitchen items were bought but nobody shared the kitchen this month'); end if;
    warnings := warnings || to_jsonb('no_kitchen_members'::text);
  elsif kitchen_total > 0 then
    select jsonb_agg(jsonb_build_object('k', d.key, 'w', d.value::int, 'o', m.join_order)) into k_items
      from jsonb_each_text(days) d join messwise.members m on m.id = d.key::uuid;
    k_a := messwise.alloc(kitchen_total, k_items, pl.round_to_rupee);
  end if;

  with ms as (
    select m.* from messwise.members m where m.place_id = p_place
  ), cups as (
    select member_id,
           sum(cups) filter (where subject = 'member') own,
           sum(cups) filter (where subject = 'personal_guest') guests
      from messwise.tea_counts where place_id = p_place and day between m0 and m1 and member_id is not null group by member_id
  ), meal_sh as (
    select s.member_id, sum(s.share_paisa) v, count(*) n from messwise.entry_shares s join messwise.meals ml on ml.id = s.entry_id
     where s.entry_type = 'meal' and ml.place_id = p_place and ml.status = 'active' and ml.day between m0 and m1 group by s.member_id
  ), party_sh as (
    select s.member_id, sum(s.share_paisa) v from messwise.entry_shares s join messwise.parties pt on pt.id = s.entry_id
     where s.entry_type = 'party' and pt.place_id = p_place and pt.status = 'active' and pt.add_to_month and pt.day between m0 and m1 group by s.member_id
  ), credits as (
    select ep.member_id, sum(ep.amount_paisa) v from messwise.entry_payers ep
      left join messwise.purchases pu on ep.entry_type = 'purchase' and pu.id = ep.entry_id
      left join messwise.meals ml on ep.entry_type = 'meal' and ml.id = ep.entry_id
      left join messwise.parties pt on ep.entry_type = 'party' and pt.id = ep.entry_id
     where (pu.place_id = p_place and pu.status = 'active' and pu.bought_on between m0 and m1)
        or (ml.place_id = p_place and ml.status = 'active' and ml.day between m0 and m1)
        or (pt.place_id = p_place and pt.status = 'active' and pt.add_to_month and pt.day between m0 and m1)
     group by ep.member_id
  ), rowsq as (
    select ms.id, ms.display_name, ms.join_order, ms.on_tea, ms.on_kitchen, ms.status, ms.user_id,
           (ms.joined_on <= m1 and (ms.left_on is null or ms.left_on >= m0)) as in_month,
           coalesce(c.own, 0) cups, coalesce(c.guests, 0) guest_cups,
           coalesce((tea_a ->> ms.id::text)::bigint, 0) tea_paisa,
           (days ->> ms.id::text)::int kitchen_days,
           coalesce((k_a ->> ms.id::text)::bigint, 0) kitchen_shared_paisa,
           coalesce(mh.v, 0) meals_paisa, coalesce(mh.n, 0) meals_count,
           coalesce(ph.v, 0) party_paisa,
           coalesce(cr.v, 0) credit_paisa
      from ms left join cups c on c.member_id = ms.id
      left join meal_sh mh on mh.member_id = ms.id
      left join party_sh ph on ph.member_id = ms.id
      left join credits cr on cr.member_id = ms.id
  )
  select coalesce(jsonb_agg(jsonb_build_object(
      'member_id', id, 'name', display_name, 'linked', user_id is not null, 'status', status,
      'type', case when on_tea and on_kitchen then 'resident' when on_tea then 'day' else 'kitchen' end,
      'cups', cups, 'guest_cups', guest_cups, 'tea_paisa', tea_paisa,
      'kitchen_days', kitchen_days, 'kitchen_shared_paisa', kitchen_shared_paisa,
      'meals_paisa', meals_paisa, 'meals_count', meals_count, 'party_paisa', party_paisa,
      'credit_paisa', credit_paisa,
      'charges_paisa', tea_paisa + kitchen_shared_paisa + meals_paisa + party_paisa,
      'total_paisa', tea_paisa + kitchen_shared_paisa + meals_paisa + party_paisa - credit_paisa
    ) order by join_order), '[]'::jsonb) into rows_
  from rowsq
  where (status = 'active' and in_month) or cups > 0 or guest_cups > 0 or tea_paisa > 0 or kitchen_shared_paisa > 0 or meals_paisa > 0 or party_paisa > 0 or credit_paisa > 0;

  return jsonb_build_object(
    'month', m0, 'upto', upto,
    'status', case when messwise.month_billed(p_place, m0) then 'billed' else 'open' end,
    'tea', jsonb_build_object('total_paisa', tea_total, 'cups_members', cups_members, 'cups_personal', cups_personal,
                              'cups_office', cups_office, 'cups_total', cups_total, 'tea_members', n_tea,
                              'rate', case when cups_total > 0 then round(tea_total::numeric / cups_total / 100, 2) end,
                              'office_share_paisa', case when cups_total > 0 and n_tea > 0 then round(tea_total::numeric * cups_office / cups_total / n_tea) end),
    'kitchen', jsonb_build_object('shared_paisa', kitchen_total, 'member_days', member_days,
                                  'per_day', case when member_days > 0 then round(kitchen_total::numeric / member_days / 100, 2) end,
                                  'meals_paisa', meals_total),
    'parties_paisa', parties_total,
    'rows', rows_,
    'warnings', warnings
  );
end $$;

-- ---------------------------------------------------------------- bills

-- Status of a bill from its accepted and waiting payments.
create or replace function messwise.bill_json(b messwise.bills) returns jsonb language plpgsql stable as $$
declare
  settled bigint;
  waiting bigint;
  remaining bigint;
  st text;
  mem messwise.members;
  pt messwise.parties;
begin
  select coalesce(sum(case when direction = 'in' then amount_paisa else -amount_paisa end) filter (where status = 'accepted'), 0),
         coalesce(sum(amount_paisa) filter (where status = 'sent'), 0)
    into settled, waiting from messwise.payments where bill_id = b.id;
  remaining := b.total_paisa - settled;
  st := case
    when b.total_paisa > 0 and remaining <= 0 then 'paid'
    when b.total_paisa > 0 and waiting > 0 then 'slip_sent'
    when b.total_paisa > 0 and settled > 0 then 'partly_paid'
    when b.total_paisa > 0 then 'pending'
    when b.total_paisa < 0 and remaining >= 0 then 'settled'
    when b.total_paisa < 0 then 'to_receive'
    else 'nothing_due' end;
  select * into mem from messwise.members where id = b.member_id;
  if b.party_id is not null then select * into pt from messwise.parties where id = b.party_id; end if;
  return jsonb_build_object(
    'id', b.id, 'place_id', b.place_id, 'member_id', b.member_id, 'member_name', mem.display_name,
    'is_main_manager', mem.is_main_manager, 'kind', b.kind, 'month', b.month,
    'party_id', b.party_id, 'party_title', pt.title, 'party_day', pt.day,
    'tea_paisa', b.tea_paisa, 'kitchen_shared_paisa', b.kitchen_shared_paisa, 'meals_paisa', b.meals_paisa,
    'party_paisa', b.party_paisa, 'credit_paisa', b.credit_paisa, 'total_paisa', b.total_paisa,
    'settled_paisa', settled, 'waiting_paisa', waiting, 'remaining_paisa', remaining, 'status', st,
    'detail', b.detail, 'updated_at', b.updated_at);
end $$;

-- Writes (or rewrites) every party bill for one party; voided parties drop to zero.
create or replace function messwise.party_bills(p_party uuid) returns void language plpgsql as $$
declare
  pt messwise.parties;
  sh jsonb := '{}'::jsonb;
  r record;
begin
  select * into pt from messwise.parties where id = p_party;
  if pt.status = 'active' and not pt.add_to_month then
    select coalesce(jsonb_object_agg(member_id::text, share_paisa), '{}'::jsonb) into sh
      from messwise.entry_shares where entry_type = 'party' and entry_id = p_party;
  end if;
  for r in
    select m.id,
           coalesce((sh ->> m.id::text)::bigint, 0) share,
           coalesce((select amount_paisa from messwise.entry_payers ep where ep.entry_type = 'party' and ep.entry_id = p_party and ep.member_id = m.id and pt.status = 'active' and not pt.add_to_month), 0) paid
      from messwise.members m
     where m.place_id = pt.place_id
       and (sh ? m.id::text
            or exists (select 1 from messwise.entry_payers ep where ep.entry_type = 'party' and ep.entry_id = p_party and ep.member_id = m.id)
            or exists (select 1 from messwise.bills b where b.party_id = p_party and b.member_id = m.id))
  loop
    insert into messwise.bills(place_id, member_id, kind, month, party_id, party_paisa, credit_paisa, total_paisa, detail)
    values (pt.place_id, r.id, 'party', messwise.month_of(pt.day), p_party, r.share, r.paid, r.share - r.paid,
            jsonb_build_object('title', pt.title, 'day', pt.day))
    on conflict (member_id, party_id) where kind = 'party' do update set
      party_paisa = excluded.party_paisa, credit_paisa = excluded.credit_paisa, total_paisa = excluded.total_paisa,
      detail = excluded.detail, updated_at = now();
  end loop;
end $$;

-- ================= supabase/migrations/0003_api.sql
-- Messwise API. Every function is SECURITY DEFINER, checks the caller's role itself and returns JSON.
-- Names start with mw_ so they never clash with Comitto's functions in the same project.

-- ---------------------------------------------------------------- JSON shapes

create or replace function messwise.member_type(m messwise.members) returns text language sql immutable as $$
  select case when m.on_tea and m.on_kitchen then 'resident' when m.on_tea then 'day' else 'kitchen' end
$$;

create or replace function messwise.member_json(m messwise.members) returns jsonb language plpgsql stable as $$
declare lv messwise.leaves;
begin
  select * into lv from messwise.leaves
   where member_id = m.id and status = 'active' and (to_date is null or to_date >= messwise.today())
   order by from_date limit 1;
  return jsonb_build_object(
    'id', m.id, 'name', m.display_name, 'role', m.role, 'is_main_manager', m.is_main_manager,
    'type', messwise.member_type(m), 'on_tea', m.on_tea, 'on_kitchen', m.on_kitchen,
    'status', m.status, 'joined_on', m.joined_on, 'left_on', m.left_on, 'join_order', m.join_order,
    'linked', m.user_id is not null, 'is_me', m.user_id is not distinct from auth.uid() and m.user_id is not null,
    'on_leave_today', messwise.on_leave(m.id, messwise.today()),
    'leave', case when lv.id is not null then jsonb_build_object('id', lv.id, 'from_date', lv.from_date, 'to_date', lv.to_date, 'note', lv.note) end);
end $$;

create or replace function messwise.can_json(m messwise.members) returns jsonb language plpgsql stable as $$
declare admins int;
begin
  select count(*) into admins from messwise.members where place_id = m.place_id and status = 'active' and role in ('owner','manager');
  return jsonb_build_object('manage', messwise.is_admin(m), 'owner', m.role = 'owner', 'sole_admin', messwise.is_admin(m) and admins = 1);
end $$;

create or replace function messwise.place_json(pl messwise.places, me messwise.members) returns jsonb language plpgsql stable as $$
begin
  return jsonb_build_object(
    'id', pl.id, 'name', pl.name, 'address', pl.address, 'kind', pl.kind, 'logo_path', pl.logo_path,
    'join_code', case when messwise.is_admin(me) then pl.join_code end,
    'round_to_rupee', pl.round_to_rupee, 'party_office_split', pl.party_office_split,
    'me', messwise.member_json(me), 'can', messwise.can_json(me),
    'managers', coalesce((select jsonb_agg(jsonb_build_object('id', id, 'name', display_name, 'role', role, 'is_main_manager', is_main_manager)
                                           order by is_main_manager desc, role = 'owner' desc, join_order)
                            from messwise.members where place_id = pl.id and status = 'active' and role in ('owner','manager')), '[]'::jsonb),
    'member_count', (select count(*) from messwise.members where place_id = pl.id and status = 'active'));
end $$;

create or replace function messwise.payers_json(p_type text, p_id uuid) returns jsonb language sql stable as $$
  select coalesce(jsonb_agg(jsonb_build_object('member_id', ep.member_id, 'name', m.display_name, 'amount_paisa', ep.amount_paisa) order by m.join_order), '[]'::jsonb)
  from messwise.entry_payers ep join messwise.members m on m.id = ep.member_id
  where ep.entry_type = p_type and ep.entry_id = p_id
$$;

create or replace function messwise.shares_json(p_type text, p_id uuid) returns jsonb language sql stable as $$
  select coalesce(jsonb_agg(jsonb_build_object('member_id', s.member_id, 'name', m.display_name, 'share_paisa', s.share_paisa) order by m.join_order), '[]'::jsonb)
  from messwise.entry_shares s join messwise.members m on m.id = s.member_id
  where s.entry_type = p_type and s.entry_id = p_id
$$;

create or replace function messwise.creator_name(p_place uuid, p_user uuid) returns text language sql stable as $$
  select display_name from messwise.members where place_id = p_place and user_id = p_user order by status = 'active' desc limit 1
$$;

create or replace function messwise.purchase_json(pu messwise.purchases) returns jsonb language sql stable as $$
  select jsonb_build_object('type', 'purchase', 'id', pu.id, 'place_id', pu.place_id, 'bill', pu.bill, 'item', pu.item,
    'amount_paisa', pu.amount_paisa, 'day', pu.bought_on, 'note', pu.note, 'receipt_path', pu.receipt_path,
    'status', pu.status, 'void_reason', pu.void_reason, 'version', pu.version,
    'created_by_name', messwise.creator_name(pu.place_id, pu.created_by), 'created_at', pu.created_at, 'updated_at', pu.updated_at,
    'payers', messwise.payers_json('purchase', pu.id))
$$;

create or replace function messwise.meal_json(ml messwise.meals) returns jsonb language sql stable as $$
  select jsonb_build_object('type', 'meal', 'id', ml.id, 'place_id', ml.place_id, 'kind', ml.kind, 'title', ml.title,
    'amount_paisa', ml.total_paisa, 'day', ml.day, 'note', ml.note, 'receipt_path', ml.receipt_path,
    'status', ml.status, 'void_reason', ml.void_reason, 'version', ml.version,
    'created_by_name', messwise.creator_name(ml.place_id, ml.created_by), 'created_at', ml.created_at, 'updated_at', ml.updated_at,
    'people', coalesce((select jsonb_agg(jsonb_build_object('subject', mp.subject, 'member_id', mp.member_id, 'name', m.display_name,
                                                            'units', mp.units, 'exact_paisa', mp.exact_paisa))
                          from messwise.meal_people mp left join messwise.members m on m.id = mp.member_id where mp.meal_id = ml.id), '[]'::jsonb),
    'payers', messwise.payers_json('meal', ml.id), 'shares', messwise.shares_json('meal', ml.id))
$$;

create or replace function messwise.party_json(pt messwise.parties) returns jsonb language plpgsql stable as $$
declare heads int; bills jsonb;
begin
  select coalesce(sum(p.heads), 0) into heads from messwise.party_people p where p.party_id = pt.id;
  select coalesce(jsonb_agg(messwise.bill_json(b) order by m.join_order), '[]'::jsonb) into bills
    from messwise.bills b join messwise.members m on m.id = b.member_id where b.party_id = pt.id;
  return jsonb_build_object('type', 'party', 'id', pt.id, 'place_id', pt.place_id, 'title', pt.title,
    'amount_paisa', pt.total_paisa, 'day', pt.day, 'office_split', pt.office_split, 'add_to_month', pt.add_to_month,
    'note', pt.note, 'receipt_path', pt.receipt_path, 'status', pt.status, 'void_reason', pt.void_reason, 'version', pt.version,
    'created_by_name', messwise.creator_name(pt.place_id, pt.created_by), 'created_at', pt.created_at, 'updated_at', pt.updated_at,
    'heads', heads, 'per_head_paisa', case when heads > 0 then round(pt.total_paisa::numeric / heads) end,
    'people', coalesce((select jsonb_agg(jsonb_build_object('subject', pp.subject, 'member_id', pp.member_id, 'name', m.display_name, 'heads', pp.heads))
                          from messwise.party_people pp left join messwise.members m on m.id = pp.member_id where pp.party_id = pt.id), '[]'::jsonb),
    'payers', messwise.payers_json('party', pt.id), 'shares', messwise.shares_json('party', pt.id), 'bills', bills);
end $$;

-- Checks a payers list ([{member_id, amount_paisa}]) against the place and total; empty = the caller paid it all.
create or replace function messwise.clean_payers(p_place uuid, p_payers jsonb, p_total bigint, me messwise.members) returns jsonb language plpgsql stable as $$
declare
  out jsonb := '[]'::jsonb;
  s bigint := 0;
  r record;
begin
  if p_payers is null or jsonb_typeof(p_payers) <> 'array' or jsonb_array_length(p_payers) = 0 then
    return jsonb_build_array(jsonb_build_object('member_id', me.id, 'amount_paisa', p_total));
  end if;
  for r in select (i ->> 'member_id')::uuid mid, (i ->> 'amount_paisa')::bigint amt from jsonb_array_elements(p_payers) i loop
    perform messwise.member_of(p_place, r.mid);
    if r.amt is null or r.amt <= 0 then perform messwise.err('INVALID', 'each payer needs an amount above zero'); end if;
    if out @> jsonb_build_array(jsonb_build_object('member_id', r.mid)) then perform messwise.err('DUPLICATE', 'a payer is listed twice'); end if;
    out := out || jsonb_build_array(jsonb_build_object('member_id', r.mid, 'amount_paisa', r.amt));
    s := s + r.amt;
  end loop;
  if s <> p_total then perform messwise.err('INVALID', 'the paid amounts must add up to the total'); end if;
  return out;
end $$;

create or replace function messwise.save_payers(p_type text, p_id uuid, p_payers jsonb) returns void language plpgsql as $$
begin
  delete from messwise.entry_payers where entry_type = p_type and entry_id = p_id;
  insert into messwise.entry_payers(entry_type, entry_id, member_id, amount_paisa)
  select p_type, p_id, (i ->> 'member_id')::uuid, (i ->> 'amount_paisa')::bigint from jsonb_array_elements(p_payers) i;
end $$;

create or replace function messwise.need_reason(p_reason text) returns text language plpgsql immutable as $$
begin
  if p_reason is null or length(trim(p_reason)) < 3 then perform messwise.err('INVALID', 'please give a reason for the change'); end if;
  return trim(p_reason);
end $$;

-- Tells each member whose share of a meal or party changed: old and new amount.
create or replace function messwise.notify_share_changes(p_place uuid, p_title text, p_old jsonb, p_new jsonb, p_reason text) returns void language plpgsql as $$
declare r record;
begin
  for r in
    select k, coalesce((p_old ->> k)::bigint, 0) o, coalesce((p_new ->> k)::bigint, 0) n
    from (select jsonb_object_keys(coalesce(p_old, '{}'::jsonb)) k union select jsonb_object_keys(coalesce(p_new, '{}'::jsonb))) keys
  loop
    if r.o <> r.n then
      perform messwise.notify(p_place, r.k, 'share_changed', p_title,
        'Your share changed from ' || messwise.rs(r.o) || ' to ' || messwise.rs(r.n) || coalesce('. Reason: ' || p_reason, ''),
        jsonb_build_object('old_paisa', r.o, 'new_paisa', r.n));
    end if;
  end loop;
end $$;

-- ---------------------------------------------------------------- account

create or replace function public.mw_app_config() returns jsonb language sql stable security definer
set search_path = messwise, extensions, pg_temp as $$
  select jsonb_build_object(
    'google_web_client_id', coalesce((select value #>> '{}' from messwise.settings where key = 'google_web_client_id'), ''),
    'min_app_version', coalesce((select value from messwise.settings where key = 'min_app_version'), '1'::jsonb),
    'support_email', coalesce((select value #>> '{}' from messwise.settings where key = 'support_email'), 'kodawick4+messwise@gmail.com'))
$$;

create or replace function public.mw_me() returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  p messwise.profiles := messwise.ensure_profile();
begin
  return jsonb_build_object(
    'profile', jsonb_build_object('user_id', p.user_id, 'full_name', p.full_name, 'email', p.email, 'language', p.language, 'budget_paisa', p.budget_paisa),
    'places', coalesce((select jsonb_agg(jsonb_build_object(
        'id', pl.id, 'name', pl.name, 'kind', pl.kind, 'address', pl.address, 'logo_path', pl.logo_path,
        'member_id', m.id, 'role', m.role, 'is_main_manager', m.is_main_manager, 'type', messwise.member_type(m),
        'pending_paisa', (select coalesce(sum(greatest((messwise.bill_json(b) ->> 'remaining_paisa')::bigint, 0)), 0)
                            from messwise.bills b where b.member_id = m.id and b.total_paisa > 0))
        order by pl.name)
      from messwise.members m join messwise.places pl on pl.id = m.place_id
      where m.user_id = p.user_id and m.status = 'active' and pl.archived_at is null), '[]'::jsonb),
    'pending_requests', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'place_name', pl.name, 'created_at', r.created_at))
      from messwise.join_requests r join messwise.places pl on pl.id = r.place_id where r.user_id = p.user_id and r.status = 'pending'), '[]'::jsonb),
    'unread', (select count(*) from messwise.notifications where user_id = p.user_id and read_at is null));
end $$;

create or replace function public.mw_profile_update(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare u uuid := messwise.uid();
begin
  perform messwise.ensure_profile();
  if p ? 'full_name' and length(trim(p ->> 'full_name')) not between 1 and 60 then perform messwise.err('INVALID', 'name must be 1 to 60 characters'); end if;
  if p ? 'language' and (p ->> 'language') not in ('en','ur') then perform messwise.err('INVALID', 'language must be en or ur'); end if;
  update messwise.profiles set full_name = coalesce(trim(p ->> 'full_name'), full_name), language = coalesce(p ->> 'language', language), updated_at = now()
   where user_id = u;
  return public.mw_me();
end $$;

-- ---------------------------------------------------------------- places

create or replace function public.mw_place_create(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  u uuid := messwise.uid();
  pr messwise.profiles := messwise.ensure_profile();
  pl messwise.places;
  me messwise.members;
  code text;
  t text := coalesce(p ->> 'owner_type', 'resident');
  nm text := trim(coalesce(p ->> 'my_name', pr.full_name, ''));
begin
  if (select count(*) from messwise.members where user_id = u and status = 'active') >= 10 then
    perform messwise.err('LIMIT', 'you can belong to up to 10 offices or hostels');
  end if;
  if coalesce(p ->> 'kind', 'hostel') not in ('hostel','office','flat','other') then perform messwise.err('INVALID', 'unknown type'); end if;
  if t not in ('resident','day','kitchen') then perform messwise.err('INVALID', 'unknown member type'); end if;
  if length(nm) = 0 then perform messwise.err('INVALID', 'your name is needed'); end if;
  loop
    code := messwise.gen_code(6);
    exit when not exists (select 1 from messwise.places where join_code = code);
  end loop;
  insert into messwise.places(name, address, kind, join_code, owner_user_id)
  values (trim(p ->> 'name'), trim(coalesce(p ->> 'address', '')), coalesce(p ->> 'kind', 'hostel'), code, u)
  returning * into pl;
  insert into messwise.members(place_id, user_id, display_name, role, is_main_manager, on_tea, on_kitchen, joined_on, join_order)
  values (pl.id, u, nm, 'owner', true, t in ('resident','day'), t in ('resident','kitchen'), messwise.today(), 1)
  returning * into me;
  perform messwise.log(pl.id, 'place_created', 'place', pl.id, pl.name);
  return messwise.place_json(pl, me);
end $$;

create or replace function public.mw_place_get(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.me_in(p_place);
begin
  return messwise.place_json(messwise.place(p_place), me);
end $$;

create or replace function public.mw_place_update(p_place uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_owner(p_place); pl messwise.places;
begin
  if p ? 'kind' and (p ->> 'kind') not in ('hostel','office','flat','other') then perform messwise.err('INVALID', 'unknown type'); end if;
  if p ? 'party_office_split' and (p ->> 'party_office_split') not in ('all','attendees') then perform messwise.err('INVALID', 'unknown split'); end if;
  update messwise.places set
    name = coalesce(trim(p ->> 'name'), name),
    address = coalesce(trim(p ->> 'address'), address),
    kind = coalesce(p ->> 'kind', kind),
    logo_path = case when p ? 'logo_path' then nullif(p ->> 'logo_path', '') else logo_path end,
    round_to_rupee = coalesce((p ->> 'round_to_rupee')::boolean, round_to_rupee),
    party_office_split = coalesce(p ->> 'party_office_split', party_office_split)
  where id = p_place returning * into pl;
  perform messwise.log(p_place, 'place_updated', 'place', p_place, pl.name);
  return messwise.place_json(pl, me);
end $$;

create or replace function public.mw_place_new_code(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_admin(p_place); code text; pl messwise.places;
begin
  loop
    code := messwise.gen_code(6);
    exit when not exists (select 1 from messwise.places where join_code = code);
  end loop;
  update messwise.places set join_code = code where id = p_place returning * into pl;
  perform messwise.log(p_place, 'code_changed', 'place', p_place, 'New invite code');
  return messwise.place_json(pl, me);
end $$;

-- ---------------------------------------------------------------- joining

create or replace function public.mw_join(p_code text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  u uuid := messwise.uid();
  pr messwise.profiles := messwise.ensure_profile();
  pl messwise.places;
  r messwise.join_requests;
begin
  select * into pl from messwise.places where join_code = upper(trim(p_code)) and archived_at is null;
  if not found then perform messwise.err('NOT_FOUND', 'no office or hostel has this code'); end if;
  if exists (select 1 from messwise.members where place_id = pl.id and user_id = u and status = 'active') then
    return jsonb_build_object('status', 'member', 'place_id', pl.id, 'place_name', pl.name);
  end if;
  select * into r from messwise.join_requests where place_id = pl.id and user_id = u and status = 'pending';
  if not found then
    insert into messwise.join_requests(place_id, user_id, full_name) values (pl.id, u, coalesce(nullif(pr.full_name, ''), 'New member')) returning * into r;
    perform messwise.notify(pl.id, 'admins', 'join_request', 'Join request', r.full_name || ' wants to join ' || pl.name, jsonb_build_object('request_id', r.id));
  end if;
  return jsonb_build_object('status', 'requested', 'place_id', pl.id, 'place_name', pl.name, 'request_id', r.id);
end $$;

create or replace function public.mw_join_requests(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_admin(p_place);
begin
  return coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'full_name', r.full_name, 'created_at', r.created_at,
      'email', (select email from messwise.profiles where user_id = r.user_id)) order by r.created_at)
    from messwise.join_requests r where r.place_id = p_place and r.status = 'pending'), '[]'::jsonb);
end $$;

-- p: {approve, type: resident|day|kitchen, link_member_id (an unlinked member added by the manager)}
create or replace function public.mw_join_decide(p_request uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  r messwise.join_requests;
  me messwise.members;
  m messwise.members;
  t text := coalesce(p ->> 'type', 'resident');
  link uuid := nullif(p ->> 'link_member_id', '')::uuid;
begin
  select * into r from messwise.join_requests where id = p_request and status = 'pending' for update;
  if not found then perform messwise.err('NOT_FOUND', 'request not found'); end if;
  me := messwise.require_admin(r.place_id);
  if not coalesce((p ->> 'approve')::boolean, false) then
    update messwise.join_requests set status = 'declined', decided_by = me.user_id, decided_at = now() where id = r.id;
    return public.mw_join_requests(r.place_id);
  end if;
  if t not in ('resident','day','kitchen') then perform messwise.err('INVALID', 'unknown member type'); end if;
  if (select count(*) from messwise.members where place_id = r.place_id and status = 'active') >= 50 then
    perform messwise.err('LIMIT', 'an office or hostel can have up to 50 members');
  end if;
  if link is not null then
    select * into m from messwise.members where id = link and place_id = r.place_id and user_id is null and status = 'active' for update;
    if not found then perform messwise.err('NOT_FOUND', 'that member is not available to link'); end if;
    update messwise.members set user_id = r.user_id where id = m.id returning * into m;
  else
    insert into messwise.members(place_id, user_id, display_name, on_tea, on_kitchen, joined_on, join_order)
    values (r.place_id, r.user_id, r.full_name, t in ('resident','day'), t in ('resident','kitchen'), messwise.today(),
            coalesce((select max(join_order) from messwise.members where place_id = r.place_id), 0) + 1)
    returning * into m;
  end if;
  update messwise.join_requests set status = 'approved', member_id = m.id, decided_by = me.user_id, decided_at = now() where id = r.id;
  insert into messwise.notifications(user_id, place_id, kind, title, body)
  values (r.user_id, r.place_id, 'joined', 'Welcome', 'You are now a member of ' || (select name from messwise.places where id = r.place_id));
  perform messwise.log(r.place_id, 'member_joined', 'member', m.id, m.display_name);
  return public.mw_join_requests(r.place_id);
end $$;

-- ---------------------------------------------------------------- members

create or replace function public.mw_members(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.me_in(p_place);
begin
  return coalesce((select jsonb_agg(messwise.member_json(m) order by m.status, m.join_order)
    from messwise.members m where m.place_id = p_place), '[]'::jsonb);
end $$;

-- A member without the app (or before they install it); they can be linked when they join.
create or replace function public.mw_member_add(p_place uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_admin(p_place); m messwise.members; t text := coalesce(p ->> 'type', 'resident');
begin
  if t not in ('resident','day','kitchen') then perform messwise.err('INVALID', 'unknown member type'); end if;
  if (select count(*) from messwise.members where place_id = p_place and status = 'active') >= 50 then
    perform messwise.err('LIMIT', 'an office or hostel can have up to 50 members');
  end if;
  insert into messwise.members(place_id, display_name, on_tea, on_kitchen, joined_on, join_order)
  values (p_place, trim(p ->> 'name'), t in ('resident','day'), t in ('resident','kitchen'),
          coalesce((p ->> 'joined_on')::date, messwise.today()),
          coalesce((select max(join_order) from messwise.members where place_id = p_place), 0) + 1)
  returning * into m;
  perform messwise.log(p_place, 'member_added', 'member', m.id, m.display_name);
  return messwise.member_json(m);
end $$;

create or replace function public.mw_member_update(p_member uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  m messwise.members;
  me messwise.members;
  t text := p ->> 'type';
begin
  select * into m from messwise.members where id = p_member;
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  me := messwise.me_in(m.place_id);
  if not messwise.is_admin(me) and me.id <> m.id then perform messwise.err('FORBIDDEN', 'only the owner or the Canteen Manager can do this'); end if;
  if t is not null then
    if not messwise.is_admin(me) then perform messwise.err('FORBIDDEN', 'only the owner or the Canteen Manager can change bills'); end if;
    if t not in ('resident','day','kitchen') then perform messwise.err('INVALID', 'unknown member type'); end if;
  end if;
  if p ? 'name' and length(trim(p ->> 'name')) not between 1 and 60 then perform messwise.err('INVALID', 'name must be 1 to 60 characters'); end if;
  update messwise.members set
    display_name = coalesce(trim(p ->> 'name'), display_name),
    on_tea = case when t is null then on_tea else t in ('resident','day') end,
    on_kitchen = case when t is null then on_kitchen else t in ('resident','kitchen') end
  where id = p_member returning * into m;
  perform messwise.log(m.place_id, 'member_updated', 'member', m.id, m.display_name || coalesce(' · ' || t, ''));
  return messwise.member_json(m);
end $$;

-- p: {role: manager|member, main: true to make the main Canteen Manager}
create or replace function public.mw_member_role(p_member uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  m messwise.members;
  me messwise.members;
  new_role text := p ->> 'role';
  main boolean := coalesce((p ->> 'main')::boolean, false);
begin
  select * into m from messwise.members where id = p_member and status = 'active';
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  me := messwise.require_owner(m.place_id);
  if m.user_id is null then perform messwise.err('STATE', 'only members who use the app can be managers'); end if;
  if m.role <> 'owner' then
    if new_role not in ('manager','member') then perform messwise.err('INVALID', 'role must be manager or member'); end if;
    if new_role = 'manager' and m.role <> 'manager' and (select count(*) from messwise.members where place_id = m.place_id and status = 'active' and role = 'manager') >= 3 then
      perform messwise.err('LIMIT', 'up to three Canteen Managers (one main and two deputies)');
    end if;
    update messwise.members set role = new_role, is_main_manager = case when new_role = 'member' then false else is_main_manager end where id = m.id;
  end if;
  if main then
    update messwise.members set is_main_manager = false where place_id = m.place_id and is_main_manager;
    update messwise.members set is_main_manager = true where id = m.id;
  end if;
  if not exists (select 1 from messwise.members where place_id = m.place_id and is_main_manager) then
    update messwise.members set is_main_manager = true where place_id = m.place_id and role = 'owner';
  end if;
  select * into m from messwise.members where id = p_member;
  perform messwise.log(m.place_id, 'role_changed', 'member', m.id, m.display_name || ': ' || m.role || case when m.is_main_manager then ' (main Canteen Manager)' else '' end);
  perform messwise.notify(m.place_id, m.id::text, 'role', 'Your role', case when m.is_main_manager then 'You are now the Canteen Manager' when m.role = 'manager' then 'You are now a deputy Canteen Manager' else 'You are now a member' end);
  return messwise.member_json(m);
end $$;

create or replace function public.mw_owner_transfer(p_member uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare m messwise.members; me messwise.members;
begin
  select * into m from messwise.members where id = p_member and status = 'active';
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  me := messwise.require_owner(m.place_id);
  if m.id = me.id then return messwise.member_json(m); end if;
  if m.user_id is null then perform messwise.err('STATE', 'the new owner must use the app'); end if;
  update messwise.members set role = 'manager' where id = me.id;
  update messwise.members set role = 'owner' where id = m.id;
  update messwise.places set owner_user_id = m.user_id where id = m.place_id;
  perform messwise.log(m.place_id, 'owner_changed', 'member', m.id, 'Owner: ' || m.display_name);
  perform messwise.notify(m.place_id, m.id::text, 'role', 'Your role', 'You are now the owner');
  select * into m from messwise.members where id = p_member;
  return messwise.member_json(m);
end $$;

-- A member leaves the office or hostel. p: {left_on, write_off_reason (owner only, to clear pending bills)}
create or replace function public.mw_member_leave_place(p_member uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  m messwise.members;
  me messwise.members;
  d date := coalesce((p ->> 'left_on')::date, messwise.today());
  pending bigint;
  b messwise.bills;
  left_ bigint;
begin
  select * into m from messwise.members where id = p_member and status = 'active';
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  me := messwise.require_admin(m.place_id);
  if m.role = 'owner' then perform messwise.err('STATE', 'hand over ownership first'); end if;
  if d < m.joined_on then perform messwise.err('INVALID', 'the last day is before they joined'); end if;
  select coalesce(sum((messwise.bill_json(x) ->> 'remaining_paisa')::bigint), 0) into pending
    from messwise.bills x where x.member_id = m.id and x.total_paisa > 0;
  if pending > 0 then
    if me.role <> 'owner' or coalesce(length(trim(p ->> 'write_off_reason')), 0) < 3 then
      perform messwise.err('STATE', 'they still have ' || messwise.rs(pending) || ' pending; mark it paid first (the owner can write it off with a reason)');
    end if;
    for b in select x.* from messwise.bills x where x.member_id = m.id and x.total_paisa > 0 loop
      left_ := (messwise.bill_json(b) ->> 'remaining_paisa')::bigint;
      if left_ > 0 then
        insert into messwise.payments(place_id, bill_id, member_id, direction, amount_paisa, method, note, status, sent_by, decided_by, decided_at)
        values (m.place_id, b.id, m.id, 'in', left_, 'other', 'Written off: ' || trim(p ->> 'write_off_reason'), 'accepted', me.user_id, me.user_id, now());
      end if;
    end loop;
  end if;
  update messwise.members set status = 'left', left_on = d, role = 'member', is_main_manager = false where id = m.id returning * into m;
  perform messwise.log(m.place_id, 'member_left', 'member', m.id, m.display_name || ' left on ' || d);
  return messwise.member_json(m);
end $$;

create or replace function public.mw_member_get(p_member uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  m messwise.members;
  me messwise.members;
  mon date := messwise.month_of(coalesce(p_month, messwise.today()));
  calc jsonb;
begin
  select * into m from messwise.members where id = p_member;
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  me := messwise.me_in(m.place_id);
  calc := messwise.month_calc(m.place_id, mon, messwise.today());
  return messwise.member_json(m) || jsonb_build_object(
    'month', mon,
    'month_row', (select r from jsonb_array_elements(calc -> 'rows') r where r ->> 'member_id' = m.id::text),
    'leaves', coalesce((select jsonb_agg(jsonb_build_object('id', l.id, 'from_date', l.from_date, 'to_date', l.to_date, 'note', l.note) order by l.from_date desc)
                         from messwise.leaves l where l.member_id = m.id and l.status = 'active'), '[]'::jsonb),
    'bills', coalesce((select jsonb_agg(messwise.bill_json(b) order by b.month desc, b.kind) from messwise.bills b where b.member_id = m.id), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------- leave

create or replace function public.mw_leave_add(p_member uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  m messwise.members;
  me messwise.members;
  f date := (p ->> 'from_date')::date;
  t date := nullif(p ->> 'to_date', '')::date;
  lv messwise.leaves;
begin
  select * into m from messwise.members where id = p_member and status = 'active';
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  me := messwise.me_in(m.place_id);
  if not messwise.is_admin(me) and me.id <> m.id then perform messwise.err('FORBIDDEN', 'you can only mark your own leave'); end if;
  if f is null then perform messwise.err('INVALID', 'start date is required'); end if;
  if t is not null and t < f then perform messwise.err('INVALID', 'the end date is before the start date'); end if;
  if messwise.month_billed(m.place_id, f) then perform messwise.err('STATE', 'that month is already billed'); end if;
  if exists (select 1 from messwise.leaves l where l.member_id = m.id and l.status = 'active'
              and l.from_date <= coalesce(t, 'infinity'::date) and coalesce(l.to_date, 'infinity'::date) >= f) then
    perform messwise.err('DUPLICATE', 'this overlaps a leave already marked');
  end if;
  insert into messwise.leaves(member_id, from_date, to_date, note, created_by)
  values (m.id, f, t, trim(coalesce(p ->> 'note', '')), me.user_id) returning * into lv;
  perform messwise.log(m.place_id, 'leave_added', 'member', m.id, m.display_name || ': leave from ' || f || coalesce(' to ' || t, ' until back'));
  if me.id = m.id then
    perform messwise.notify(m.place_id, 'admins', 'leave', 'Leave', m.display_name || ' is on leave from ' || to_char(f, 'DD Mon') || coalesce(' to ' || to_char(t, 'DD Mon'), ' until back'));
  else
    perform messwise.notify(m.place_id, m.id::text, 'leave', 'Leave', 'You are marked on leave from ' || to_char(f, 'DD Mon') || coalesce(' to ' || to_char(t, 'DD Mon'), ' until back'));
  end if;
  return messwise.member_json(m);
end $$;

-- Back on p_back_on: the leave ends the day before.
create or replace function public.mw_leave_end(p_leave uuid, p_back_on date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  lv messwise.leaves;
  m messwise.members;
  me messwise.members;
  back date := coalesce(p_back_on, messwise.today());
begin
  select * into lv from messwise.leaves where id = p_leave and status = 'active' for update;
  if not found then perform messwise.err('NOT_FOUND', 'leave not found'); end if;
  select * into m from messwise.members where id = lv.member_id;
  me := messwise.me_in(m.place_id);
  if not messwise.is_admin(me) and me.id <> m.id then perform messwise.err('FORBIDDEN', 'you can only change your own leave'); end if;
  if back <= lv.from_date then
    update messwise.leaves set status = 'void' where id = lv.id;
  else
    update messwise.leaves set to_date = back - 1 where id = lv.id;
  end if;
  perform messwise.log(m.place_id, 'leave_ended', 'member', m.id, m.display_name || ' back on ' || back);
  if me.id = m.id then perform messwise.notify(m.place_id, 'admins', 'leave', 'Back', m.display_name || ' is back from ' || to_char(back, 'DD Mon')); end if;
  return messwise.member_json(m);
end $$;

create or replace function public.mw_leave_void(p_leave uuid, p_reason text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare lv messwise.leaves; m messwise.members; me messwise.members; why text := messwise.need_reason(p_reason);
begin
  select * into lv from messwise.leaves where id = p_leave and status = 'active';
  if not found then perform messwise.err('NOT_FOUND', 'leave not found'); end if;
  select * into m from messwise.members where id = lv.member_id;
  me := messwise.require_admin(m.place_id);
  if messwise.month_billed(m.place_id, lv.from_date) then perform messwise.err('STATE', 'that month is already billed'); end if;
  update messwise.leaves set status = 'void' where id = lv.id;
  perform messwise.log(m.place_id, 'leave_void', 'member', m.id, m.display_name || ': leave removed (' || why || ')');
  return messwise.member_json(m);
end $$;

-- ---------------------------------------------------------------- purchases (Tea Bill and Kitchen Bill)

-- p: {id?, place_id, bill: tea|kitchen, item, amount_paisa, bought_on, note, receipt_path, payers: [{member_id, amount_paisa}], reason (edits)}
create or replace function public.mw_purchase_save(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.require_admin(pl);
  pid uuid := nullif(p ->> 'id', '')::uuid;
  cur messwise.purchases;
  pu messwise.purchases;
  amt bigint := (p ->> 'amount_paisa')::bigint;
  d date := (p ->> 'bought_on')::date;
  payers jsonb;
  why text;
begin
  if (p ->> 'bill') not in ('tea','kitchen') then perform messwise.err('INVALID', 'choose Tea Bill or Kitchen Bill'); end if;
  if length(trim(coalesce(p ->> 'item', ''))) = 0 then perform messwise.err('INVALID', 'what was bought?'); end if;
  if amt is null or amt <= 0 then perform messwise.err('INVALID', 'amount must be above zero'); end if;
  perform messwise.require_open(pl, d);
  payers := messwise.clean_payers(pl, p -> 'payers', amt, me);
  if pid is null then
    insert into messwise.purchases(place_id, bill, item, amount_paisa, bought_on, note, receipt_path, created_by)
    values (pl, p ->> 'bill', trim(p ->> 'item'), amt, d, trim(coalesce(p ->> 'note', '')), nullif(p ->> 'receipt_path', ''), me.user_id)
    returning * into pu;
    perform messwise.log(pl, 'purchase_added', 'purchase', pu.id, initcap(pu.bill) || ': ' || pu.item || ' ' || messwise.rs(pu.amount_paisa));
  else
    select * into cur from messwise.purchases where id = pid and place_id = pl for update;
    if not found then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
    if cur.status = 'void' then perform messwise.err('STATE', 'this entry was voided'); end if;
    perform messwise.require_open(pl, cur.bought_on);
    why := messwise.need_reason(p ->> 'reason');
    insert into messwise.entry_versions(entry_type, entry_id, version, snapshot, reason, changed_by)
    values ('purchase', cur.id, cur.version, messwise.purchase_json(cur), why, me.user_id);
    update messwise.purchases set bill = p ->> 'bill', item = trim(p ->> 'item'), amount_paisa = amt, bought_on = d,
      note = trim(coalesce(p ->> 'note', '')), receipt_path = coalesce(nullif(p ->> 'receipt_path', ''), receipt_path),
      version = version + 1, updated_by = me.user_id, updated_at = now()
    where id = pid returning * into pu;
    perform messwise.log(pl, 'purchase_edited', 'purchase', pu.id, pu.item || ': ' || messwise.rs(cur.amount_paisa) || ' to ' || messwise.rs(pu.amount_paisa) || ' (' || why || ')');
    perform messwise.notify(pl, 'all', 'entry_edited', 'Entry edited',
      initcap(pu.bill) || ' Bill: ' || pu.item || ' ' || messwise.rs(cur.amount_paisa) || ' changed to ' || messwise.rs(pu.amount_paisa) || '. Reason: ' || why,
      jsonb_build_object('entry_type', 'purchase', 'entry_id', pu.id));
  end if;
  perform messwise.save_payers('purchase', pu.id, payers);
  return messwise.purchase_json(pu);
end $$;

create or replace function public.mw_purchase_void(p_id uuid, p_reason text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare pu messwise.purchases; me messwise.members; why text := messwise.need_reason(p_reason);
begin
  select * into pu from messwise.purchases where id = p_id for update;
  if not found then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
  me := messwise.require_admin(pu.place_id);
  if pu.status = 'void' then return messwise.purchase_json(pu); end if;
  perform messwise.require_open(pu.place_id, pu.bought_on);
  insert into messwise.entry_versions(entry_type, entry_id, version, snapshot, reason, changed_by)
  values ('purchase', pu.id, pu.version, messwise.purchase_json(pu), 'Voided: ' || why, me.user_id);
  update messwise.purchases set status = 'void', void_reason = why, version = version + 1, updated_by = me.user_id, updated_at = now()
   where id = p_id returning * into pu;
  perform messwise.log(pu.place_id, 'purchase_voided', 'purchase', pu.id, pu.item || ' ' || messwise.rs(pu.amount_paisa) || ' (' || why || ')');
  perform messwise.notify(pu.place_id, 'all', 'entry_voided', 'Entry voided', initcap(pu.bill) || ' Bill: ' || pu.item || ' ' || messwise.rs(pu.amount_paisa) || ' was voided. Reason: ' || why,
    jsonb_build_object('entry_type', 'purchase', 'entry_id', pu.id));
  return messwise.purchase_json(pu);
end $$;

-- ---------------------------------------------------------------- official tea count

create or replace function public.mw_tea_day(p_place uuid, p_day date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  d date := coalesce(p_day, messwise.today());
  calc jsonb := messwise.month_calc(p_place, messwise.month_of(d), messwise.today());
begin
  return jsonb_build_object(
    'day', d, 'month_status', calc ->> 'status', 'can_edit', messwise.is_admin(me) and not messwise.month_billed(p_place, d),
    'rows', coalesce((select jsonb_agg(jsonb_build_object(
        'member_id', m.id, 'name', m.display_name, 'on_tea', m.on_tea, 'on_leave', messwise.on_leave(m.id, d),
        'cups', coalesce((select cups from messwise.tea_counts t where t.place_id = p_place and t.day = d and t.subject = 'member' and t.member_id = m.id), 0),
        'guest_cups', coalesce((select cups from messwise.tea_counts t where t.place_id = p_place and t.day = d and t.subject = 'personal_guest' and t.member_id = m.id), 0))
        order by not m.on_tea, m.join_order)
      from messwise.members m where m.place_id = p_place and messwise.belongs(m, d)
        and (m.on_tea or exists (select 1 from messwise.tea_counts t where t.place_id = p_place and t.day = d and t.member_id = m.id))), '[]'::jsonb),
    'office_cups', coalesce((select cups from messwise.tea_counts where place_id = p_place and day = d and subject = 'office_guest'), 0),
    'day_total', coalesce((select sum(cups) from messwise.tea_counts where place_id = p_place and day = d), 0),
    'month', calc -> 'tea');
end $$;

-- p_rows: [{subject: member|personal_guest|office_guest, member_id, cups}] — cups 0 clears the count.
create or replace function public.mw_tea_set(p_place uuid, p_day date, p_rows jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.require_admin(p_place);
  r record;
  changed int := 0;
begin
  perform messwise.require_open(p_place, p_day);
  for r in select i ->> 'subject' subj, nullif(i ->> 'member_id', '')::uuid mid, coalesce((i ->> 'cups')::int, 0) cups from jsonb_array_elements(coalesce(p_rows, '[]'::jsonb)) i loop
    if r.subj not in ('member','personal_guest','office_guest') then perform messwise.err('INVALID', 'unknown tea row'); end if;
    if (r.subj = 'office_guest') <> (r.mid is null) then perform messwise.err('INVALID', 'tea row needs a member'); end if;
    if r.mid is not null then perform messwise.member_of(p_place, r.mid); end if;
    if r.cups < 0 or r.cups > 300 then perform messwise.err('INVALID', 'cups must be 0 to 300'); end if;
    delete from messwise.tea_counts where place_id = p_place and day = p_day and subject = r.subj and member_id is not distinct from r.mid;
    if r.cups > 0 then
      insert into messwise.tea_counts(place_id, day, subject, member_id, cups, updated_by) values (p_place, p_day, r.subj, r.mid, r.cups, me.user_id);
    end if;
    changed := changed + 1;
  end loop;
  if changed > 0 then perform messwise.log(p_place, 'tea_counted', 'tea', null, 'Tea count for ' || to_char(p_day, 'DD Mon')); end if;
  return public.mw_tea_day(p_place, p_day);
end $$;

-- Tea round: one cup more for each member ticked.
create or replace function public.mw_tea_round(p_place uuid, p_day date, p_members jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_admin(p_place); mid uuid; n int := 0;
begin
  perform messwise.require_open(p_place, p_day);
  for mid in select (x #>> '{}')::uuid from jsonb_array_elements(coalesce(p_members, '[]'::jsonb)) x loop
    n := n + 1;
    perform messwise.member_of(p_place, mid);
    update messwise.tea_counts set cups = least(cups + 1, 300), updated_by = me.user_id, updated_at = now()
     where place_id = p_place and day = p_day and subject = 'member' and member_id = mid;
    if not found then
      insert into messwise.tea_counts(place_id, day, subject, member_id, cups, updated_by) values (p_place, p_day, 'member', mid, 1, me.user_id);
    end if;
  end loop;
  perform messwise.log(p_place, 'tea_round', 'tea', null, 'Tea round for ' || n || ' on ' || to_char(p_day, 'DD Mon'));
  return public.mw_tea_day(p_place, p_day);
end $$;

-- ---------------------------------------------------------------- my private tea tally

create or replace function public.mw_my_tea_add(p_place uuid, p_cups int default 1) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.me_in(p_place); d date := messwise.today();
begin
  if coalesce(p_cups, 1) not between 1 and 5 then perform messwise.err('INVALID', 'add 1 to 5 cups at a time'); end if;
  if (select count(*) from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day = d) >= 60 then
    perform messwise.err('LIMIT', 'that is a lot of tea for one day');
  end if;
  insert into messwise.my_tea_marks(user_id, place_id, day, cups) values (me.user_id, p_place, d, coalesce(p_cups, 1));
  return jsonb_build_object('day', d, 'today_cups', (select coalesce(sum(cups), 0) from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day = d));
end $$;

create or replace function public.mw_my_tea_undo(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.me_in(p_place); d date := messwise.today();
begin
  delete from messwise.my_tea_marks where id = (select id from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day = d order by id desc limit 1);
  return jsonb_build_object('day', d, 'today_cups', (select coalesce(sum(cups), 0) from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day = d));
end $$;

-- My tally beside the manager's official count, day by day.
create or replace function public.mw_my_tea(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  m1 date := messwise.month_end(m0);
begin
  return jsonb_build_object('month', m0,
    'days', coalesce((select jsonb_agg(jsonb_build_object('day', d, 'mine', mine, 'official', official) order by d) from (
        select g.d::date d,
          coalesce((select sum(cups) from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day = g.d::date), 0) mine,
          coalesce((select cups from messwise.tea_counts where place_id = p_place and day = g.d::date and subject = 'member' and member_id = me.id), 0) official
        from generate_series(m0, least(m1, messwise.today()), interval '1 day') g(d)) x where mine > 0 or official > 0), '[]'::jsonb),
    'mine_total', (select coalesce(sum(cups), 0) from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day between m0 and m1),
    'official_total', (select coalesce(sum(cups), 0) from messwise.tea_counts where place_id = p_place and day between m0 and m1 and subject = 'member' and member_id = me.id),
    'tea', messwise.month_calc(p_place, m0, messwise.today()) -> 'tea');
end $$;

-- ---------------------------------------------------------------- meals

-- p: {id?, place_id, day, kind, title, amount_paisa, people: [{subject, member_id, units, exact_paisa}], payers, note, receipt_path, reason}
create or replace function public.mw_meal_save(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.require_admin(pl);
  mid uuid := nullif(p ->> 'id', '')::uuid;
  cur messwise.meals;
  ml messwise.meals;
  amt bigint := (p ->> 'amount_paisa')::bigint;
  d date := (p ->> 'day')::date;
  payers jsonb;
  old_sh jsonb := '{}'::jsonb;
  new_sh jsonb;
  why text;
  r record;
begin
  if coalesce(p ->> 'kind', '') not in ('breakfast','lunch','dinner','other') then perform messwise.err('INVALID', 'choose breakfast, lunch, dinner or other'); end if;
  if amt is null or amt <= 0 then perform messwise.err('INVALID', 'amount must be above zero'); end if;
  if jsonb_typeof(p -> 'people') <> 'array' or jsonb_array_length(p -> 'people') = 0 then perform messwise.err('INVALID', 'tick who ate'); end if;
  perform messwise.require_open(pl, d);
  payers := messwise.clean_payers(pl, p -> 'payers', amt, me);
  if mid is null then
    insert into messwise.meals(place_id, day, kind, title, total_paisa, note, receipt_path, created_by)
    values (pl, d, p ->> 'kind', trim(coalesce(p ->> 'title', '')), amt, trim(coalesce(p ->> 'note', '')), nullif(p ->> 'receipt_path', ''), me.user_id)
    returning * into ml;
  else
    select * into cur from messwise.meals where id = mid and place_id = pl for update;
    if not found then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
    if cur.status = 'void' then perform messwise.err('STATE', 'this entry was voided'); end if;
    perform messwise.require_open(pl, cur.day);
    why := messwise.need_reason(p ->> 'reason');
    select coalesce(jsonb_object_agg(member_id::text, share_paisa), '{}'::jsonb) into old_sh from messwise.entry_shares where entry_type = 'meal' and entry_id = mid;
    insert into messwise.entry_versions(entry_type, entry_id, version, snapshot, reason, changed_by)
    values ('meal', cur.id, cur.version, messwise.meal_json(cur), why, me.user_id);
    update messwise.meals set day = d, kind = p ->> 'kind', title = trim(coalesce(p ->> 'title', '')), total_paisa = amt,
      note = trim(coalesce(p ->> 'note', '')), receipt_path = coalesce(nullif(p ->> 'receipt_path', ''), receipt_path),
      version = version + 1, updated_by = me.user_id, updated_at = now()
    where id = mid returning * into ml;
    delete from messwise.meal_people where meal_id = ml.id;
  end if;
  for r in select i ->> 'subject' subj, nullif(i ->> 'member_id', '')::uuid mem, coalesce((i ->> 'units')::numeric, 1) u, (i ->> 'exact_paisa')::bigint ex
           from jsonb_array_elements(p -> 'people') i loop
    if r.subj not in ('member','personal_guest','office_guest') then perform messwise.err('INVALID', 'unknown person'); end if;
    if r.mem is not null then perform messwise.member_of(pl, r.mem); end if;
    insert into messwise.meal_people(meal_id, subject, member_id, units, exact_paisa) values (ml.id, r.subj, r.mem, r.u, r.ex);
  end loop;
  if (select count(*) from messwise.meal_people where meal_id = ml.id and subject = 'member') <>
     (select count(distinct member_id) from messwise.meal_people where meal_id = ml.id and subject = 'member') then
    perform messwise.err('DUPLICATE', 'a member is ticked twice');
  end if;
  new_sh := messwise.meal_shares(ml.id);
  delete from messwise.entry_shares where entry_type = 'meal' and entry_id = ml.id;
  insert into messwise.entry_shares(entry_type, entry_id, member_id, share_paisa)
  select 'meal', ml.id, key::uuid, value::bigint from jsonb_each_text(new_sh) where value::bigint > 0;
  perform messwise.save_payers('meal', ml.id, payers);
  if mid is null then
    perform messwise.log(pl, 'meal_added', 'meal', ml.id, initcap(ml.kind) || ' ' || to_char(ml.day, 'DD Mon') || ' ' || messwise.rs(ml.total_paisa));
  else
    perform messwise.log(pl, 'meal_edited', 'meal', ml.id, initcap(ml.kind) || ' ' || to_char(ml.day, 'DD Mon') || ': ' || messwise.rs(cur.total_paisa) || ' to ' || messwise.rs(ml.total_paisa) || ' (' || why || ')');
    perform messwise.notify_share_changes(pl, initcap(ml.kind) || ' on ' || to_char(ml.day, 'DD Mon') || ' edited', old_sh, new_sh, why);
  end if;
  return messwise.meal_json(ml);
end $$;

create or replace function public.mw_meal_void(p_id uuid, p_reason text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare ml messwise.meals; me messwise.members; why text := messwise.need_reason(p_reason); old_sh jsonb;
begin
  select * into ml from messwise.meals where id = p_id for update;
  if not found then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
  me := messwise.require_admin(ml.place_id);
  if ml.status = 'void' then return messwise.meal_json(ml); end if;
  perform messwise.require_open(ml.place_id, ml.day);
  select coalesce(jsonb_object_agg(member_id::text, share_paisa), '{}'::jsonb) into old_sh from messwise.entry_shares where entry_type = 'meal' and entry_id = p_id;
  insert into messwise.entry_versions(entry_type, entry_id, version, snapshot, reason, changed_by)
  values ('meal', ml.id, ml.version, messwise.meal_json(ml), 'Voided: ' || why, me.user_id);
  update messwise.meals set status = 'void', void_reason = why, version = version + 1, updated_by = me.user_id, updated_at = now() where id = p_id returning * into ml;
  perform messwise.log(ml.place_id, 'meal_voided', 'meal', ml.id, initcap(ml.kind) || ' ' || to_char(ml.day, 'DD Mon') || ' (' || why || ')');
  perform messwise.notify_share_changes(ml.place_id, initcap(ml.kind) || ' on ' || to_char(ml.day, 'DD Mon') || ' voided', old_sh, '{}'::jsonb, why);
  return messwise.meal_json(ml);
end $$;

-- ---------------------------------------------------------------- parties

-- p: {id?, place_id, day, title, amount_paisa, office_split, add_to_month, people: [{subject, member_id, heads}], payers, note, receipt_path, reason}
create or replace function public.mw_party_save(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.require_admin(pl);
  place_ messwise.places := messwise.place(pl);
  ptid uuid := nullif(p ->> 'id', '')::uuid;
  cur messwise.parties;
  pt messwise.parties;
  amt bigint := (p ->> 'amount_paisa')::bigint;
  d date := (p ->> 'day')::date;
  split text := coalesce(p ->> 'office_split', place_.party_office_split);
  to_month boolean := coalesce((p ->> 'add_to_month')::boolean, false);
  payers jsonb;
  old_sh jsonb := '{}'::jsonb;
  new_sh jsonb;
  why text;
  r record;
begin
  if length(trim(coalesce(p ->> 'title', ''))) = 0 then perform messwise.err('INVALID', 'give the party a name'); end if;
  if amt is null or amt <= 0 then perform messwise.err('INVALID', 'amount must be above zero'); end if;
  if split not in ('all','attendees') then perform messwise.err('INVALID', 'unknown split'); end if;
  if jsonb_typeof(p -> 'people') <> 'array' or jsonb_array_length(p -> 'people') = 0 then perform messwise.err('INVALID', 'add who attended'); end if;
  if d is null or d > messwise.today() + 60 then perform messwise.err('INVALID', 'choose the party date'); end if;
  if to_month then perform messwise.require_open(pl, d); end if;
  payers := messwise.clean_payers(pl, p -> 'payers', amt, me);
  if ptid is null then
    insert into messwise.parties(place_id, day, title, total_paisa, office_split, add_to_month, note, receipt_path, created_by)
    values (pl, d, trim(p ->> 'title'), amt, split, to_month, trim(coalesce(p ->> 'note', '')), nullif(p ->> 'receipt_path', ''), me.user_id)
    returning * into pt;
  else
    select * into cur from messwise.parties where id = ptid and place_id = pl for update;
    if not found then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
    if cur.status = 'void' then perform messwise.err('STATE', 'this entry was voided'); end if;
    if cur.add_to_month then perform messwise.require_open(pl, cur.day); end if;
    why := messwise.need_reason(p ->> 'reason');
    select coalesce(jsonb_object_agg(member_id::text, share_paisa), '{}'::jsonb) into old_sh from messwise.entry_shares where entry_type = 'party' and entry_id = ptid;
    insert into messwise.entry_versions(entry_type, entry_id, version, snapshot, reason, changed_by)
    values ('party', cur.id, cur.version, messwise.party_json(cur), why, me.user_id);
    update messwise.parties set day = d, title = trim(p ->> 'title'), total_paisa = amt, office_split = split, add_to_month = to_month,
      note = trim(coalesce(p ->> 'note', '')), receipt_path = coalesce(nullif(p ->> 'receipt_path', ''), receipt_path),
      version = version + 1, updated_by = me.user_id, updated_at = now()
    where id = ptid returning * into pt;
    delete from messwise.party_people where party_id = pt.id;
  end if;
  for r in select i ->> 'subject' subj, nullif(i ->> 'member_id', '')::uuid mem, coalesce((i ->> 'heads')::int, 1) h
           from jsonb_array_elements(p -> 'people') i loop
    if r.subj not in ('member','personal_guest','office_guest') then perform messwise.err('INVALID', 'unknown person'); end if;
    if r.mem is not null then perform messwise.member_of(pl, r.mem); end if;
    if r.subj = 'member' then r.h := 1; end if;
    insert into messwise.party_people(party_id, subject, member_id, heads) values (pt.id, r.subj, r.mem, r.h);
  end loop;
  if (select count(*) from messwise.party_people where party_id = pt.id and subject = 'member') <>
     (select count(distinct member_id) from messwise.party_people where party_id = pt.id and subject = 'member') then
    perform messwise.err('DUPLICATE', 'a member is listed twice');
  end if;
  new_sh := messwise.party_shares(pt.id);
  delete from messwise.entry_shares where entry_type = 'party' and entry_id = pt.id;
  insert into messwise.entry_shares(entry_type, entry_id, member_id, share_paisa)
  select 'party', pt.id, key::uuid, value::bigint from jsonb_each_text(new_sh) where value::bigint > 0;
  perform messwise.save_payers('party', pt.id, payers);
  perform messwise.party_bills(pt.id);
  if ptid is null then
    perform messwise.log(pl, 'party_added', 'party', pt.id, pt.title || ' ' || messwise.rs(pt.total_paisa));
    for r in select key k, value::bigint v from jsonb_each_text(new_sh) where value::bigint > 0 loop
      perform messwise.notify(pl, r.k, 'party', pt.title, 'Your share: ' || messwise.rs(r.v), jsonb_build_object('party_id', pt.id));
    end loop;
  else
    perform messwise.log(pl, 'party_edited', 'party', pt.id, pt.title || ': ' || messwise.rs(cur.total_paisa) || ' to ' || messwise.rs(pt.total_paisa) || ' (' || why || ')');
    perform messwise.notify_share_changes(pl, pt.title || ' edited', old_sh, new_sh, why);
  end if;
  return messwise.party_json(pt);
end $$;

create or replace function public.mw_party_void(p_id uuid, p_reason text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare pt messwise.parties; me messwise.members; why text := messwise.need_reason(p_reason); old_sh jsonb;
begin
  select * into pt from messwise.parties where id = p_id for update;
  if not found then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
  me := messwise.require_admin(pt.place_id);
  if pt.status = 'void' then return messwise.party_json(pt); end if;
  if pt.add_to_month then perform messwise.require_open(pt.place_id, pt.day); end if;
  if exists (select 1 from messwise.payments py join messwise.bills b on b.id = py.bill_id where b.party_id = p_id and py.status in ('accepted','sent')) then
    perform messwise.err('STATE', 'payments are recorded for this party; send them back or pay them out first');
  end if;
  select coalesce(jsonb_object_agg(member_id::text, share_paisa), '{}'::jsonb) into old_sh from messwise.entry_shares where entry_type = 'party' and entry_id = p_id;
  insert into messwise.entry_versions(entry_type, entry_id, version, snapshot, reason, changed_by)
  values ('party', pt.id, pt.version, messwise.party_json(pt), 'Voided: ' || why, me.user_id);
  update messwise.parties set status = 'void', void_reason = why, version = version + 1, updated_by = me.user_id, updated_at = now() where id = p_id returning * into pt;
  perform messwise.party_bills(pt.id);
  perform messwise.log(pt.place_id, 'party_voided', 'party', pt.id, pt.title || ' (' || why || ')');
  perform messwise.notify_share_changes(pt.place_id, pt.title || ' voided', old_sh, '{}'::jsonb, why);
  return messwise.party_json(pt);
end $$;

-- ---------------------------------------------------------------- entries list and detail

create or replace function public.mw_entries(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  m1 date := messwise.month_end(m0);
begin
  return jsonb_build_object('month', m0, 'items', coalesce((select jsonb_agg(x order by x ->> 'day' desc, x ->> 'created_at' desc) from (
      select messwise.purchase_json(pu) x from messwise.purchases pu where pu.place_id = p_place and pu.bought_on between m0 and m1
      union all
      select messwise.meal_json(ml) from messwise.meals ml where ml.place_id = p_place and ml.day between m0 and m1
      union all
      select messwise.party_json(pt) from messwise.parties pt where pt.place_id = p_place and pt.day between m0 and m1
    ) s), '[]'::jsonb));
end $$;

create or replace function public.mw_entry_get(p_type text, p_id uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid;
  me messwise.members;
  e jsonb;
begin
  if p_type = 'purchase' then select place_id, messwise.purchase_json(x) into pl, e from messwise.purchases x where id = p_id;
  elsif p_type = 'meal' then select place_id, messwise.meal_json(x) into pl, e from messwise.meals x where id = p_id;
  elsif p_type = 'party' then select place_id, messwise.party_json(x) into pl, e from messwise.parties x where id = p_id;
  else perform messwise.err('INVALID', 'unknown entry');
  end if;
  if pl is null then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
  me := messwise.me_in(pl);
  return e || jsonb_build_object(
    'versions', coalesce((select jsonb_agg(jsonb_build_object('version', v.version, 'reason', v.reason, 'changed_at', v.changed_at,
        'changed_by_name', messwise.creator_name(pl, v.changed_by), 'snapshot', v.snapshot) order by v.id desc)
      from messwise.entry_versions v where v.entry_type = p_type and v.entry_id = p_id), '[]'::jsonb),
    'questions', coalesce((select jsonb_agg(jsonb_build_object('id', q.id, 'text', q.text, 'status', q.status, 'answer', q.answer,
        'asked_by', m.display_name, 'created_at', q.created_at) order by q.created_at)
      from messwise.questions q join messwise.members m on m.id = q.member_id
      where q.entity = p_type and q.entity_id = p_id and (messwise.is_admin(me) or q.member_id = me.id)), '[]'::jsonb),
    'can_edit', messwise.is_admin(me));
end $$;

-- ---------------------------------------------------------------- months and bills

create or replace function public.mw_month(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  mo messwise.months;
  calc jsonb;
begin
  if m0 > messwise.month_of(messwise.today()) then perform messwise.err('INVALID', 'that month has not started'); end if;
  select * into mo from messwise.months where place_id = p_place and month = m0;
  if found and mo.status = 'billed' then
    calc := mo.summary;
  else
    calc := messwise.month_calc(p_place, m0, messwise.today());
  end if;
  return calc || jsonb_build_object(
    'status', coalesce(mo.status, 'open'), 'billed_at', mo.billed_at, 'reopen_reason', mo.reopen_reason,
    'can_bill', messwise.is_admin(me) and coalesce(mo.status, 'open') = 'open',
    'can_reopen', me.role = 'owner' and mo.status = 'billed',
    'bills', coalesce((select jsonb_agg(messwise.bill_json(b) order by m.join_order) from messwise.bills b join messwise.members m on m.id = b.member_id
                        where b.place_id = p_place and b.kind = 'month' and b.month = m0), '[]'::jsonb));
end $$;

-- Makes the final Tea and Kitchen bills for a month and locks its entries.
create or replace function public.mw_month_bill(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.require_admin(p_place);
  m0 date := messwise.month_of(p_month);
  calc jsonb;
  r jsonb;
  b messwise.bills;
  label text := to_char(m0, 'Mon YYYY');
begin
  if m0 > messwise.month_of(messwise.today()) then perform messwise.err('INVALID', 'that month has not started'); end if;
  if messwise.month_billed(p_place, m0) then perform messwise.err('STATE', 'bills for ' || label || ' are already made'); end if;
  calc := messwise.month_calc(p_place, m0, messwise.month_end(m0), true);
  for r in select x from jsonb_array_elements(calc -> 'rows') x loop
    if (r ->> 'charges_paisa')::bigint = 0 and (r ->> 'credit_paisa')::bigint = 0
       and not exists (select 1 from messwise.bills where member_id = (r ->> 'member_id')::uuid and kind = 'month' and month = m0) then
      continue;
    end if;
    insert into messwise.bills(place_id, member_id, kind, month, tea_paisa, kitchen_shared_paisa, meals_paisa, party_paisa, credit_paisa, total_paisa, detail)
    values (p_place, (r ->> 'member_id')::uuid, 'month', m0, (r ->> 'tea_paisa')::bigint, (r ->> 'kitchen_shared_paisa')::bigint,
            (r ->> 'meals_paisa')::bigint, (r ->> 'party_paisa')::bigint, (r ->> 'credit_paisa')::bigint, (r ->> 'total_paisa')::bigint,
            jsonb_build_object('cups', r -> 'cups', 'guest_cups', r -> 'guest_cups', 'kitchen_days', r -> 'kitchen_days',
                               'meals_count', r -> 'meals_count', 'tea_rate', calc #> '{tea,rate}', 'per_day', calc #> '{kitchen,per_day}'))
    on conflict (member_id, month) where kind = 'month' do update set
      tea_paisa = excluded.tea_paisa, kitchen_shared_paisa = excluded.kitchen_shared_paisa, meals_paisa = excluded.meals_paisa,
      party_paisa = excluded.party_paisa, credit_paisa = excluded.credit_paisa, total_paisa = excluded.total_paisa,
      detail = excluded.detail, updated_at = now()
    returning * into b;
    -- Advances paid before the bill was ready count against it.
    update messwise.payments set bill_id = b.id where bill_id is null and member_id = b.member_id and direction = 'in' and status in ('accepted','sent');
    if b.total_paisa > 0 then
      perform messwise.notify(p_place, b.member_id::text, 'bill', label || ' bill',
        'Your bill is ' || messwise.rs(b.total_paisa) || ' (Tea ' || messwise.rs(b.tea_paisa) || ', Kitchen ' || messwise.rs(b.kitchen_shared_paisa + b.meals_paisa) || ')',
        jsonb_build_object('bill_id', b.id), true);
    elsif b.total_paisa < 0 then
      perform messwise.notify(p_place, b.member_id::text, 'bill', label || ' bill', 'You get back ' || messwise.rs(-b.total_paisa), jsonb_build_object('bill_id', b.id), true);
    end if;
  end loop;
  insert into messwise.months(place_id, month, status, summary, billed_by, billed_at)
  values (p_place, m0, 'billed', calc, me.user_id, now())
  on conflict (place_id, month) do update set status = 'billed', summary = excluded.summary, billed_by = excluded.billed_by, billed_at = now();
  perform messwise.log(p_place, 'month_billed', 'month', null, label || ': tea rate ' || coalesce(calc #>> '{tea,rate}', '-') || ', total ' ||
    messwise.rs((calc #>> '{tea,total_paisa}')::bigint + (calc #>> '{kitchen,shared_paisa}')::bigint + (calc #>> '{kitchen,meals_paisa}')::bigint));
  return public.mw_month(p_place, m0);
end $$;

create or replace function public.mw_month_reopen(p_place uuid, p_month date, p_reason text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_owner(p_place); m0 date := messwise.month_of(p_month); why text := messwise.need_reason(p_reason);
begin
  update messwise.months set status = 'open', reopened_by = me.user_id, reopened_at = now(), reopen_reason = why where place_id = p_place and month = m0 and status = 'billed';
  if not found then perform messwise.err('STATE', 'that month is not billed'); end if;
  perform messwise.log(p_place, 'month_reopened', 'month', null, to_char(m0, 'Mon YYYY') || ' reopened (' || why || ')');
  perform messwise.notify(p_place, 'admins', 'month', 'Month reopened', to_char(m0, 'Mon YYYY') || ' was reopened: ' || why);
  return public.mw_month(p_place, m0);
end $$;

create or replace function public.mw_bills(p_place uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  mon date := nullif(p ->> 'month', '')::date;
  unpaid boolean := coalesce((p ->> 'unpaid')::boolean, false);
  mem uuid := nullif(p ->> 'member_id', '')::uuid;
begin
  return coalesce((select jsonb_agg(x order by x ->> 'month' desc, x ->> 'member_name') from (
      select messwise.bill_json(b) x from messwise.bills b
      where b.place_id = p_place and (mon is null or b.month = messwise.month_of(mon)) and (mem is null or b.member_id = mem)) s
    where not unpaid or (x ->> 'remaining_paisa')::bigint > 0), '[]'::jsonb);
end $$;

create or replace function public.mw_bill_get(p_bill uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  b messwise.bills;
  me messwise.members;
  m0 date; m1 date;
  own boolean;
begin
  select * into b from messwise.bills where id = p_bill;
  if not found then perform messwise.err('NOT_FOUND', 'bill not found'); end if;
  me := messwise.me_in(b.place_id);
  own := me.id = b.member_id;
  m0 := b.month; m1 := messwise.month_end(b.month);
  return messwise.bill_json(b) || jsonb_build_object(
    'is_mine', own, 'can_mark', messwise.is_admin(me) and (not own or (messwise.can_json(me) ->> 'sole_admin')::boolean),
    'meals', case when b.kind = 'month' then coalesce((select jsonb_agg(jsonb_build_object('id', ml.id, 'day', ml.day, 'kind', ml.kind, 'title', ml.title, 'share_paisa', s.share_paisa) order by ml.day)
        from messwise.entry_shares s join messwise.meals ml on ml.id = s.entry_id
        where s.entry_type = 'meal' and s.member_id = b.member_id and ml.status = 'active' and ml.day between m0 and m1), '[]'::jsonb) end,
    'paid_for_group', coalesce((select jsonb_agg(x) from (
        select jsonb_build_object('type', 'purchase', 'id', pu.id, 'title', pu.item, 'bill', pu.bill, 'day', pu.bought_on, 'amount_paisa', ep.amount_paisa) x
          from messwise.entry_payers ep join messwise.purchases pu on pu.id = ep.entry_id
         where b.kind = 'month' and ep.entry_type = 'purchase' and ep.member_id = b.member_id and pu.status = 'active' and pu.bought_on between m0 and m1
        union all
        select jsonb_build_object('type', 'meal', 'id', ml.id, 'title', initcap(ml.kind), 'day', ml.day, 'amount_paisa', ep.amount_paisa)
          from messwise.entry_payers ep join messwise.meals ml on ml.id = ep.entry_id
         where b.kind = 'month' and ep.entry_type = 'meal' and ep.member_id = b.member_id and ml.status = 'active' and ml.day between m0 and m1
        union all
        select jsonb_build_object('type', 'party', 'id', pt.id, 'title', pt.title, 'day', pt.day, 'amount_paisa', ep.amount_paisa)
          from messwise.entry_payers ep join messwise.parties pt on pt.id = ep.entry_id
         where ep.entry_type = 'party' and ep.member_id = b.member_id and pt.status = 'active'
           and ((b.kind = 'party' and pt.id = b.party_id) or (b.kind = 'month' and pt.add_to_month and pt.day between m0 and m1))
      ) s), '[]'::jsonb),
    'payments', coalesce((select jsonb_agg(jsonb_build_object('id', py.id, 'direction', py.direction, 'amount_paisa', py.amount_paisa, 'method', py.method,
        'slip_path', case when messwise.is_admin(me) or own then py.slip_path end, 'has_slip', py.slip_path is not null,
        'note', py.note, 'status', py.status, 'reason', py.reason, 'created_at', py.created_at, 'decided_at', py.decided_at,
        'decided_by_name', messwise.creator_name(b.place_id, py.decided_by)) order by py.created_at desc)
      from messwise.payments py where py.bill_id = b.id), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------- dashboard

create or replace function public.mw_dashboard(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  pl messwise.places := messwise.place(p_place);
  d date := messwise.today();
  calc jsonb := messwise.month_calc(p_place, messwise.month_of(d), d);
  admin boolean := messwise.is_admin(me);
begin
  return jsonb_build_object(
    'place', messwise.place_json(pl, me),
    'today', d,
    'month', jsonb_build_object('month', calc -> 'month', 'status', calc -> 'status', 'tea', calc -> 'tea', 'kitchen', calc -> 'kitchen', 'warnings', calc -> 'warnings'),
    'my_so_far', (select r from jsonb_array_elements(calc -> 'rows') r where r ->> 'member_id' = me.id::text),
    'my_bills', coalesce((select jsonb_agg(x order by x ->> 'month' desc) from (
        select messwise.bill_json(b) x from messwise.bills b where b.member_id = me.id) s
      where (x ->> 'remaining_paisa')::bigint <> 0), '[]'::jsonb),
    'advance_paisa', (select coalesce(sum(amount_paisa), 0) from messwise.payments where member_id = me.id and bill_id is null and direction = 'in' and status = 'accepted'),
    'pending', coalesce((select jsonb_agg(x order by (x ->> 'member_id') = me.id::text desc, x ->> 'month' desc, x ->> 'member_name') from (
        select messwise.bill_json(b) x from messwise.bills b where b.place_id = p_place and b.total_paisa > 0) s
      where (x ->> 'remaining_paisa')::bigint > 0), '[]'::jsonb),
    'tea_today', jsonb_build_object(
      'official', (select coalesce(sum(cups), 0) from messwise.tea_counts where place_id = p_place and day = d),
      'my_official', (select coalesce(sum(cups), 0) from messwise.tea_counts where place_id = p_place and day = d and subject = 'member' and member_id = me.id),
      'my_marks', (select coalesce(sum(cups), 0) from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day = d)),
    'meals_today', (select count(*) from messwise.meals where place_id = p_place and day = d and status = 'active'),
    'on_leave_today', coalesce((select jsonb_agg(m.display_name order by m.join_order) from messwise.members m
                                where m.place_id = p_place and m.status = 'active' and messwise.on_leave(m.id, d)), '[]'::jsonb),
    'admin', case when admin then jsonb_build_object(
      'slips_waiting', (select count(*) from messwise.payments where place_id = p_place and status = 'sent'),
      'open_questions', (select count(*) from messwise.questions where place_id = p_place and status = 'open'),
      'join_requests', (select count(*) from messwise.join_requests where place_id = p_place and status = 'pending'),
      'unbilled_months', coalesce((select jsonb_agg(mm order by mm) from (
          select distinct messwise.month_of(x.day) mm from (
            select bought_on as day from messwise.purchases where place_id = p_place and status = 'active'
            union all select day from messwise.meals where place_id = p_place and status = 'active'
            union all select day from messwise.tea_counts where place_id = p_place) x
          where messwise.month_of(x.day) < messwise.month_of(d)
            and not messwise.month_billed(p_place, x.day)) y), '[]'::jsonb)) end,
    'unread', (select count(*) from messwise.notifications where user_id = me.user_id and read_at is null));
end $$;

-- ---------------------------------------------------------------- payments

-- A member says they paid: p {place_id, bill_id (null = advance), amount_paisa, method, slip_path, note}
create or replace function public.mw_payment_send(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.me_in(pl);
  bid uuid := nullif(p ->> 'bill_id', '')::uuid;
  b messwise.bills;
  amt bigint := (p ->> 'amount_paisa')::bigint;
  py messwise.payments;
begin
  if amt is null or amt <= 0 then perform messwise.err('INVALID', 'amount must be above zero'); end if;
  if coalesce(p ->> 'method', '') not in ('cash','jazzcash','easypaisa','bank','other') then perform messwise.err('INVALID', 'choose how you paid'); end if;
  if bid is not null then
    select * into b from messwise.bills where id = bid and place_id = pl;
    if not found then perform messwise.err('NOT_FOUND', 'bill not found'); end if;
    if b.member_id <> me.id then perform messwise.err('FORBIDDEN', 'you can only pay your own bill'); end if;
    if b.total_paisa <= 0 then perform messwise.err('STATE', 'nothing is due on this bill'); end if;
  end if;
  insert into messwise.payments(place_id, bill_id, member_id, direction, amount_paisa, method, slip_path, note, status, sent_by)
  values (pl, bid, me.id, 'in', amt, p ->> 'method', nullif(p ->> 'slip_path', ''), trim(coalesce(p ->> 'note', '')), 'sent', me.user_id)
  returning * into py;
  perform messwise.notify(pl, 'admins', 'slip', 'Payment slip', me.display_name || ' sent ' || messwise.rs(amt) ||
    case when b.id is null then ' as an advance' when b.kind = 'party' then ' for ' || coalesce((select title from messwise.parties where id = b.party_id), 'a party') else ' for ' || to_char(b.month, 'Mon YYYY') end,
    jsonb_build_object('payment_id', py.id, 'bill_id', bid));
  perform messwise.log(pl, 'payment_sent', 'payment', py.id, me.display_name || ' sent ' || messwise.rs(amt));
  return case when bid is not null then public.mw_bill_get(bid) else jsonb_build_object('payment_id', py.id, 'status', 'sent') end;
end $$;

create or replace function messwise.check_can_mark(me messwise.members, p_member uuid) returns void language plpgsql stable as $$
begin
  if not messwise.is_admin(me) then perform messwise.err('FORBIDDEN', 'only the owner or the Canteen Manager can mark payments'); end if;
  if p_member = me.id and not (messwise.can_json(me) ->> 'sole_admin')::boolean then
    perform messwise.err('FORBIDDEN', 'your own bill must be marked by the owner or another manager');
  end if;
end $$;

-- p: {accept: true|false, amount_paisa (accept part of it), reason (send back)}
create or replace function public.mw_payment_decide(p_payment uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  py messwise.payments;
  me messwise.members;
  amt bigint;
  why text;
begin
  select * into py from messwise.payments where id = p_payment for update;
  if not found then perform messwise.err('NOT_FOUND', 'payment not found'); end if;
  me := messwise.me_in(py.place_id);
  perform messwise.check_can_mark(me, py.member_id);
  if py.status <> 'sent' then perform messwise.err('STATE', 'this payment was already handled'); end if;
  if coalesce((p ->> 'accept')::boolean, false) then
    amt := coalesce((p ->> 'amount_paisa')::bigint, py.amount_paisa);
    if amt <= 0 or amt > py.amount_paisa then perform messwise.err('INVALID', 'accept between Rs 0.01 and the amount sent'); end if;
    update messwise.payments set status = 'accepted', amount_paisa = amt, decided_by = me.user_id, decided_at = now(),
      note = case when amt < py.amount_paisa then trim(note || ' (part accepted: ' || messwise.rs(amt) || ' of ' || messwise.rs(py.amount_paisa) || ')') else note end
     where id = py.id returning * into py;
    perform messwise.notify(py.place_id, py.member_id::text, 'paid', 'Payment marked paid', messwise.rs(amt) || ' received by ' || me.display_name, jsonb_build_object('bill_id', py.bill_id));
    perform messwise.log(py.place_id, 'payment_accepted', 'payment', py.id, messwise.rs(amt));
  else
    why := messwise.need_reason(p ->> 'reason');
    update messwise.payments set status = 'sent_back', reason = why, decided_by = me.user_id, decided_at = now() where id = py.id returning * into py;
    perform messwise.notify(py.place_id, py.member_id::text, 'sent_back', 'Payment slip sent back', why, jsonb_build_object('bill_id', py.bill_id));
    perform messwise.log(py.place_id, 'payment_sent_back', 'payment', py.id, why);
  end if;
  return case when py.bill_id is not null then public.mw_bill_get(py.bill_id) else jsonb_build_object('payment_id', py.id, 'status', py.status) end;
end $$;

-- The manager records a payment directly (cash, no slip), or pays a member back (direction out).
-- p: {place_id, bill_id | member_id (advance), direction, amount_paisa, method, slip_path, note}
create or replace function public.mw_payment_record(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.me_in(pl);
  bid uuid := nullif(p ->> 'bill_id', '')::uuid;
  b messwise.bills;
  mem uuid;
  dir text := coalesce(p ->> 'direction', 'in');
  amt bigint := (p ->> 'amount_paisa')::bigint;
  py messwise.payments;
begin
  if amt is null or amt <= 0 then perform messwise.err('INVALID', 'amount must be above zero'); end if;
  if dir not in ('in','out') then perform messwise.err('INVALID', 'unknown direction'); end if;
  if coalesce(p ->> 'method', '') not in ('cash','jazzcash','easypaisa','bank','other') then perform messwise.err('INVALID', 'choose how it was paid'); end if;
  if bid is not null then
    select * into b from messwise.bills where id = bid and place_id = pl;
    if not found then perform messwise.err('NOT_FOUND', 'bill not found'); end if;
    mem := b.member_id;
    if dir = 'out' and b.total_paisa >= 0 then perform messwise.err('STATE', 'this member owes on this bill; nothing to pay back'); end if;
  else
    mem := (p ->> 'member_id')::uuid;
    perform messwise.member_of(pl, mem);
    if dir = 'out' then perform messwise.err('INVALID', 'paying back needs a bill'); end if;
  end if;
  perform messwise.check_can_mark(me, mem);
  insert into messwise.payments(place_id, bill_id, member_id, direction, amount_paisa, method, slip_path, note, status, sent_by, decided_by, decided_at)
  values (pl, bid, mem, dir, amt, p ->> 'method', nullif(p ->> 'slip_path', ''), trim(coalesce(p ->> 'note', '')), 'accepted', me.user_id, me.user_id, now())
  returning * into py;
  perform messwise.notify(pl, mem::text, 'paid', case when dir = 'in' then 'Payment marked paid' else 'Money paid back' end,
    messwise.rs(amt) || case when dir = 'in' then ' received by ' else ' paid to you by ' end || me.display_name, jsonb_build_object('bill_id', bid));
  perform messwise.log(pl, case when dir = 'in' then 'payment_recorded' else 'payment_out' end, 'payment', py.id, messwise.rs(amt));
  return case when bid is not null then public.mw_bill_get(bid) else jsonb_build_object('payment_id', py.id, 'status', 'accepted') end;
end $$;

create or replace function public.mw_payments_waiting(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_admin(p_place);
begin
  return coalesce((select jsonb_agg(jsonb_build_object('id', py.id, 'bill_id', py.bill_id, 'member_id', py.member_id, 'member_name', m.display_name,
      'amount_paisa', py.amount_paisa, 'method', py.method, 'slip_path', py.slip_path, 'note', py.note, 'created_at', py.created_at,
      'bill', case when b.id is not null then messwise.bill_json(b) end,
      'can_mark', m.id <> me.id or (messwise.can_json(me) ->> 'sole_admin')::boolean) order by py.created_at)
    from messwise.payments py join messwise.members m on m.id = py.member_id left join messwise.bills b on b.id = py.bill_id
    where py.place_id = p_place and py.status = 'sent'), '[]'::jsonb);
end $$;

-- Payment reminders. p {member_ids: [] (empty = everyone pending), note}. At most one per person per day.
create or replace function public.mw_remind(p_place uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.require_admin(p_place);
  ids uuid[] := array(select (x #>> '{}')::uuid from jsonb_array_elements(coalesce(p -> 'member_ids', '[]'::jsonb)) x);
  note text := trim(coalesce(p ->> 'note', ''));
  main_name text := coalesce((select display_name from messwise.members where place_id = p_place and is_main_manager), me.display_name);
  r record;
  sent int := 0;
  skipped int := 0;
begin
  for r in
    select m.id, m.user_id, m.display_name,
           (select coalesce(sum(greatest((messwise.bill_json(b) ->> 'remaining_paisa')::bigint, 0)), 0)::bigint from messwise.bills b where b.member_id = m.id and b.total_paisa > 0) pending
      from messwise.members m
     where m.place_id = p_place and m.status = 'active' and m.id <> me.id
       and (coalesce(array_length(ids, 1), 0) = 0 or m.id = any(ids))
  loop
    if r.pending <= 0 or r.user_id is null then
      if coalesce(array_length(ids, 1), 0) > 0 then skipped := skipped + 1; end if;
      continue;
    end if;
    insert into messwise.reminders(place_id, from_user, to_member, note, sent_on) values (p_place, me.user_id, r.id, note, messwise.today())
    on conflict (to_member, sent_on) do nothing;
    if not found then skipped := skipped + 1; continue; end if;
    perform messwise.notify(p_place, r.id::text, 'reminder', 'Payment reminder',
      'Your pending bill is ' || messwise.rs(r.pending) || '. Please pay ' || main_name || '.' || case when note <> '' then ' ' || note else '' end);
    sent := sent + 1;
  end loop;
  if sent > 0 then perform messwise.log(p_place, 'reminders_sent', 'payment', null, sent || ' reminder(s)'); end if;
  return jsonb_build_object('sent', sent, 'skipped', skipped);
end $$;

create or replace function public.mw_notice(p_place uuid, p_text text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.require_admin(p_place);
begin
  if length(trim(coalesce(p_text, ''))) not between 2 and 300 then perform messwise.err('INVALID', 'write a notice of 2 to 300 characters'); end if;
  perform messwise.notify(p_place, 'all', 'notice', 'Notice from ' || me.display_name, trim(p_text));
  perform messwise.log(p_place, 'notice', 'place', p_place, trim(p_text));
  return jsonb_build_object('ok', true);
end $$;

-- ---------------------------------------------------------------- questions

-- p: {place_id, entity, entity_id, month, text, share_tally}
create or replace function public.mw_question_ask(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.me_in(pl);
  q messwise.questions;
  mon date := nullif(p ->> 'month', '')::date;
  tally jsonb;
begin
  if coalesce(p ->> 'entity', '') not in ('purchase','meal','party','tea','bill','other') then perform messwise.err('INVALID', 'unknown subject'); end if;
  if coalesce((p ->> 'share_tally')::boolean, false) then
    tally := public.mw_my_tea(pl, coalesce(mon, messwise.today()));
  end if;
  insert into messwise.questions(place_id, member_id, entity, entity_id, month, text, tally)
  values (pl, me.id, p ->> 'entity', nullif(p ->> 'entity_id', '')::uuid, case when mon is not null then messwise.month_of(mon) end, trim(p ->> 'text'), tally)
  returning * into q;
  perform messwise.notify(pl, 'admins', 'question', 'Question from ' || me.display_name, left(q.text, 140), jsonb_build_object('question_id', q.id));
  return jsonb_build_object('id', q.id, 'status', q.status);
end $$;

create or replace function public.mw_question_answer(p_id uuid, p_answer text) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare q messwise.questions; me messwise.members;
begin
  select * into q from messwise.questions where id = p_id for update;
  if not found then perform messwise.err('NOT_FOUND', 'question not found'); end if;
  me := messwise.require_admin(q.place_id);
  if length(trim(coalesce(p_answer, ''))) < 2 then perform messwise.err('INVALID', 'write an answer'); end if;
  update messwise.questions set status = 'answered', answer = trim(p_answer), answered_by = me.user_id, answered_at = now() where id = p_id returning * into q;
  perform messwise.notify(q.place_id, q.member_id::text, 'answer', 'Answer from ' || me.display_name, left(q.answer, 140), jsonb_build_object('question_id', q.id));
  return public.mw_questions(q.place_id);
end $$;

create or replace function public.mw_questions(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.me_in(p_place);
begin
  return coalesce((select jsonb_agg(jsonb_build_object('id', q.id, 'entity', q.entity, 'entity_id', q.entity_id, 'month', q.month, 'text', q.text,
      'tally', q.tally, 'status', q.status, 'answer', q.answer, 'asked_by', m.display_name, 'member_id', m.id, 'created_at', q.created_at,
      'answered_at', q.answered_at) order by q.status = 'answered', q.created_at desc)
    from messwise.questions q join messwise.members m on m.id = q.member_id
    where q.place_id = p_place and (messwise.is_admin(me) or q.member_id = me.id)), '[]'::jsonb);
end $$;

-- ---------------------------------------------------------------- notifications and history

create or replace function public.mw_notifications(p_before bigint) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare u uuid := messwise.uid();
begin
  return coalesce((select jsonb_agg(jsonb_build_object('id', n.id, 'place_id', n.place_id, 'place_name', pl.name, 'kind', n.kind, 'title', n.title,
      'body', n.body, 'data', n.data, 'created_at', n.created_at, 'read', n.read_at is not null) order by n.id desc)
    from (select * from messwise.notifications where user_id = u and (p_before is null or id < p_before) order by id desc limit 50) n
    left join messwise.places pl on pl.id = n.place_id), '[]'::jsonb);
end $$;

create or replace function public.mw_notifications_read(p_ids jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare u uuid := messwise.uid();
begin
  update messwise.notifications set read_at = now() where user_id = u and read_at is null
    and (p_ids is null or jsonb_typeof(p_ids) <> 'array' or jsonb_array_length(p_ids) = 0
         or id in (select (x #>> '{}')::bigint from jsonb_array_elements(p_ids) x));
  return jsonb_build_object('unread', (select count(*) from messwise.notifications where user_id = u and read_at is null));
end $$;

create or replace function public.mw_activity(p_place uuid, p_before bigint) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.me_in(p_place);
begin
  return coalesce((select jsonb_agg(jsonb_build_object('id', a.id, 'actor_name', a.actor_name, 'action', a.action, 'entity', a.entity,
      'entity_id', a.entity_id, 'summary', a.summary, 'at', a.at) order by a.id desc)
    from (select * from messwise.activity where place_id = p_place and (p_before is null or id < p_before) order by id desc limit 60) a), '[]'::jsonb);
end $$;

-- ---------------------------------------------------------------- reports (data for the six PDF/Excel reports)

create or replace function public.mw_report(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  pl messwise.places := messwise.place(p_place);
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  m1 date := messwise.month_end(m0);
  mon jsonb := public.mw_month(p_place, m0);
begin
  return jsonb_build_object(
    'place', jsonb_build_object('name', pl.name, 'address', pl.address, 'kind', pl.kind),
    'generated_at', now(), 'generated_by', me.display_name,
    'month', mon,
    'tea_purchases', coalesce((select jsonb_agg(messwise.purchase_json(pu) order by pu.bought_on, pu.created_at)
                                 from messwise.purchases pu where pu.place_id = p_place and pu.bill = 'tea' and pu.status = 'active' and pu.bought_on between m0 and m1), '[]'::jsonb),
    'kitchen_purchases', coalesce((select jsonb_agg(messwise.purchase_json(pu) order by pu.bought_on, pu.created_at)
                                     from messwise.purchases pu where pu.place_id = p_place and pu.bill = 'kitchen' and pu.status = 'active' and pu.bought_on between m0 and m1), '[]'::jsonb),
    'meals', coalesce((select jsonb_agg(messwise.meal_json(ml) order by ml.day, ml.created_at)
                         from messwise.meals ml where ml.place_id = p_place and ml.status = 'active' and ml.day between m0 and m1), '[]'::jsonb),
    'parties', coalesce((select jsonb_agg(messwise.party_json(pt) order by pt.day)
                           from messwise.parties pt where pt.place_id = p_place and pt.status = 'active' and pt.day between m0 and m1), '[]'::jsonb),
    'tea_days', coalesce((select jsonb_agg(jsonb_build_object('day', t.day, 'subject', t.subject, 'member_id', t.member_id, 'cups', t.cups) order by t.day)
                            from messwise.tea_counts t where t.place_id = p_place and t.day between m0 and m1), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------- private expense book

create or replace function public.mw_personal(p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pr messwise.profiles := messwise.ensure_profile();
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  m1 date := messwise.month_end(m0);
  mess jsonb := '[]'::jsonb;
  r record;
  row_ jsonb;
begin
  for r in select m.id, m.place_id, pl.name from messwise.members m join messwise.places pl on pl.id = m.place_id
           where m.user_id = pr.user_id and pl.archived_at is null loop
    row_ := (select x from jsonb_array_elements(messwise.month_calc(r.place_id, m0, messwise.today()) -> 'rows') x where x ->> 'member_id' = r.id::text);
    if row_ is not null and (row_ ->> 'charges_paisa')::bigint > 0 then
      mess := mess || jsonb_build_array(jsonb_build_object('place_id', r.place_id, 'place_name', r.name, 'charges_paisa', (row_ ->> 'charges_paisa')::bigint));
    end if;
  end loop;
  return jsonb_build_object('month', m0, 'budget_paisa', pr.budget_paisa,
    'items', coalesce((select jsonb_agg(jsonb_build_object('id', e.id, 'amount_paisa', e.amount_paisa, 'category', e.category, 'note', e.note,
        'day', e.spent_on, 'receipt_path', e.receipt_path) order by e.spent_on desc, e.created_at desc)
      from messwise.personal_expenses e where e.user_id = pr.user_id and e.spent_on between m0 and m1), '[]'::jsonb),
    'by_category', coalesce((select jsonb_agg(jsonb_build_object('category', category, 'amount_paisa', s) order by s desc) from (
        select category, sum(amount_paisa) s from messwise.personal_expenses where user_id = pr.user_id and spent_on between m0 and m1 group by category) c), '[]'::jsonb),
    'personal_total_paisa', (select coalesce(sum(amount_paisa), 0) from messwise.personal_expenses where user_id = pr.user_id and spent_on between m0 and m1),
    'mess', mess);
end $$;

create or replace function public.mw_personal_save(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pr messwise.profiles := messwise.ensure_profile();
  eid uuid := nullif(p ->> 'id', '')::uuid;
  amt bigint := (p ->> 'amount_paisa')::bigint;
  d date := coalesce((p ->> 'day')::date, messwise.today());
begin
  if amt is null or amt <= 0 then perform messwise.err('INVALID', 'amount must be above zero'); end if;
  if eid is null then
    insert into messwise.personal_expenses(user_id, amount_paisa, category, note, spent_on, receipt_path)
    values (pr.user_id, amt, trim(coalesce(p ->> 'category', 'Other')), trim(coalesce(p ->> 'note', '')), d, nullif(p ->> 'receipt_path', ''));
  else
    update messwise.personal_expenses set amount_paisa = amt, category = trim(coalesce(p ->> 'category', category)), note = trim(coalesce(p ->> 'note', '')),
      spent_on = d, receipt_path = coalesce(nullif(p ->> 'receipt_path', ''), receipt_path), updated_at = now()
     where id = eid and user_id = pr.user_id;
    if not found then perform messwise.err('NOT_FOUND', 'expense not found'); end if;
  end if;
  return public.mw_personal(d);
end $$;

create or replace function public.mw_personal_delete(p_id uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare u uuid := messwise.uid(); d date;
begin
  delete from messwise.personal_expenses where id = p_id and user_id = u returning spent_on into d;
  if d is null then perform messwise.err('NOT_FOUND', 'expense not found'); end if;
  return public.mw_personal(d);
end $$;

create or replace function public.mw_budget_set(p_paisa bigint) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare pr messwise.profiles := messwise.ensure_profile();
begin
  if p_paisa is not null and p_paisa < 0 then perform messwise.err('INVALID', 'budget cannot be negative'); end if;
  update messwise.profiles set budget_paisa = nullif(p_paisa, 0), updated_at = now() where user_id = pr.user_id;
  return public.mw_personal(messwise.today());
end $$;

-- ---------------------------------------------------------------- account deletion

-- Deletes the caller's Messwise data. Shared records stay so others' bills still add up, under "Former member".
-- The sign-in itself is removed only when the person has no Comitto account in the same project.
create or replace function public.mw_account_delete() returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  u uuid := messwise.uid();
  r record;
  has_comitto boolean := false;
begin
  for r in select m.*, (select count(*) from messwise.members o where o.place_id = m.place_id and o.status = 'active' and o.id <> m.id) others
           from messwise.members m where m.user_id = u and m.status = 'active' and m.role = 'owner' loop
    if r.others > 0 then perform messwise.err('STATE', 'hand over ownership of your office or hostel first'); end if;
    update messwise.places set archived_at = now() where id = r.place_id;
  end loop;
  update messwise.members set display_name = 'Former member', user_id = null, role = 'member', is_main_manager = false,
    status = 'left', left_on = greatest(joined_on, coalesce(left_on, messwise.today()))
   where user_id = u;
  delete from messwise.personal_expenses where user_id = u;
  delete from messwise.my_tea_marks where user_id = u;
  delete from messwise.notifications where user_id = u;
  delete from messwise.join_requests where user_id = u;
  delete from messwise.profiles where user_id = u;
  if to_regclass('app.profiles') is not null then
    execute 'select exists (select 1 from app.profiles where user_id = $1)' into has_comitto using u;
  end if;
  if not has_comitto then delete from auth.users where id = u; end if;
  return jsonb_build_object('ok', true);
end $$;

-- ================= supabase/migrations/0004_storage_and_grants.sql
-- Private bucket for receipts, payment slips and logos.
-- Paths: "<place id>/receipts/<uploader id>/<file>", "<place id>/slips/<uploader id>/<file>",
-- "<place id>/logo/<uploader id>/<file>", and "personal/<user id>/<file>" for the private expense book.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('messwise', 'messwise', false, 3145728, array['image/jpeg','image/png','image/webp','application/pdf'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

create or replace function messwise.can_upload(p_name text) returns boolean language plpgsql stable security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  parts text[] := string_to_array(p_name, '/');
  u uuid := auth.uid();
  m messwise.members;
begin
  if u is null or array_length(parts, 1) is null then return false; end if;
  if parts[1] = 'personal' then return array_length(parts, 1) = 3 and parts[2] = u::text; end if;
  if array_length(parts, 1) <> 4 or parts[3] <> u::text or parts[2] not in ('receipts','slips','logo') then return false; end if;
  begin
    select * into m from messwise.members where place_id = parts[1]::uuid and user_id = u and status = 'active';
  exception when others then return false;
  end;
  if not found then return false; end if;
  if parts[2] = 'slips' then return true; end if;
  if parts[2] = 'logo' then return m.role = 'owner'; end if;
  return messwise.is_admin(m);
end $$;

create or replace function messwise.can_view(p_name text) returns boolean language plpgsql stable security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  parts text[] := string_to_array(p_name, '/');
  u uuid := auth.uid();
  m messwise.members;
begin
  if u is null or array_length(parts, 1) is null then return false; end if;
  if parts[1] = 'personal' then return parts[2] = u::text; end if;
  if array_length(parts, 1) <> 4 then return false; end if;
  begin
    select * into m from messwise.members where place_id = parts[1]::uuid and user_id = u and status = 'active';
  exception when others then return false;
  end;
  if not found then return false; end if;
  if parts[2] in ('receipts','logo') then return true; end if;
  -- Payment slips: the person who sent it and the owner or managers.
  return parts[3] = u::text or messwise.is_admin(m);
end $$;

drop policy if exists "messwise upload" on storage.objects;
drop policy if exists "messwise read" on storage.objects;
create policy "messwise upload" on storage.objects for insert to authenticated
  with check (bucket_id = 'messwise' and messwise.can_upload(name));
create policy "messwise read" on storage.objects for select to authenticated
  using (bucket_id = 'messwise' and messwise.can_view(name));

insert into messwise.settings(key, value) values ('google_web_client_id', '""') on conflict (key) do nothing;
insert into messwise.settings(key, value) values ('support_email', '"kodawick4+messwise@gmail.com"') on conflict (key) do nothing;

-- ---------------------------------------------------------------- who can call what
revoke all on all tables in schema messwise from public, anon, authenticated;
revoke all on all sequences in schema messwise from public, anon, authenticated;
revoke execute on all functions in schema messwise from public;
alter default privileges in schema messwise revoke execute on functions from public;

-- Storage policies run as the signed-in user and need only these two checks.
grant usage on schema messwise to authenticated;
grant execute on function messwise.can_upload(text) to authenticated;
grant execute on function messwise.can_view(text) to authenticated;

-- Internal helpers keep a fixed search path (Supabase linter 0011).
do $$
declare f record;
begin
  for f in
    select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'messwise' and p.prokind = 'f' and p.proconfig is null
  loop
    execute format('alter function %s set search_path = messwise, extensions, pg_temp', f.sig);
  end loop;
end $$;

-- Public API: signed-in users only, except the app settings read before sign-in.
do $$
declare f record;
begin
  for f in
    select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname like 'mw\_%'
  loop
    execute format('revoke execute on function %s from public', f.sig);
    execute format('revoke execute on function %s from anon', f.sig);
    execute format('grant execute on function %s to authenticated', f.sig);
  end loop;
end $$;
grant execute on function public.mw_app_config() to anon;

-- ================= supabase/migrations/0005_visibility.sql
-- MessWise 0005: everyone sees only what they are part of (Atif, 27 Nov 2026).
--
--   * Owner and Canteen Managers see everything (they keep and correct the accounts).
--   * Tea purchases: members on the Tea Bill. Kitchen purchases: members on the Kitchen Bill.
--   * Lunch, dinner and parties: the people who ate (or brought a guest), whoever paid, and whoever added it.
--   * Totals follow the same rule: Tea Bill figures only for tea drinkers, Kitchen Bill figures only for kitchen
--     members, group meal and party totals only for the managers. Other people's bill lines keep their total and
--     paid/pending (pending bills stay visible to everyone), without the parts the viewer is not in.
--   * Reports are made from the same filtered data, so a tea-only member gets no kitchen expenses.
--   * Notifications about an entry go only to the people who can see it.
-- Safe to run more than once.

-- ---------------------------------------------------------------- who sees an entry
create or replace function messwise.sees_entry(me messwise.members, p_type text, p_id uuid) returns boolean language plpgsql stable as $$
declare
  b text;
  creator uuid;
begin
  if me.id is null then return false; end if;
  if messwise.is_admin(me) then return true; end if;
  if exists (select 1 from messwise.entry_payers where entry_type = p_type and entry_id = p_id and member_id = me.id) then return true; end if;
  if p_type = 'purchase' then
    select bill, created_by into b, creator from messwise.purchases where id = p_id;
    return creator = me.user_id or (b = 'tea' and me.on_tea) or (b = 'kitchen' and me.on_kitchen);
  elsif p_type = 'meal' then
    return exists (select 1 from messwise.meals where id = p_id and created_by = me.user_id)
        or exists (select 1 from messwise.meal_people where meal_id = p_id and member_id = me.id)
        or exists (select 1 from messwise.entry_shares where entry_type = 'meal' and entry_id = p_id and member_id = me.id);
  elsif p_type = 'party' then
    return exists (select 1 from messwise.parties where id = p_id and created_by = me.user_id)
        or exists (select 1 from messwise.party_people where party_id = p_id and member_id = me.id)
        or exists (select 1 from messwise.entry_shares where entry_type = 'party' and entry_id = p_id and member_id = me.id)
        or exists (select 1 from messwise.bills where party_id = p_id and member_id = me.id);
  end if;
  return false;
end $$;

-- What the viewer may see of a bill: their own and (for managers) everyone's in full; other people's without
-- the parts the viewer is not in.
create or replace function messwise.bill_for(me messwise.members, b messwise.bills) returns jsonb language plpgsql stable as $$
declare j jsonb := messwise.bill_json(b);
begin
  if messwise.is_admin(me) or b.member_id = me.id then return j; end if;
  j := j - array['meals_paisa', 'party_paisa', 'credit_paisa', 'detail'];
  if not me.on_tea then j := j - 'tea_paisa'; end if;
  if not me.on_kitchen then j := j - 'kitchen_shared_paisa'; end if;
  if b.party_id is not null and not messwise.sees_entry(me, 'party', b.party_id) then j := j - array['party_title', 'party_day']; end if;
  return j;
end $$;

-- A month's figures (live or billed) as the viewer may see them, with a "sees" block for the app.
create or replace function messwise.month_view(me messwise.members, j jsonb) returns jsonb language plpgsql stable as $$
declare
  hide text[];
  rows_ jsonb;
begin
  if messwise.is_admin(me) then
    return j || jsonb_build_object('sees', jsonb_build_object('tea', true, 'kitchen', true, 'meals', true, 'everyone', true));
  end if;
  hide := array['meals_paisa', 'meals_count', 'party_paisa', 'credit_paisa', 'charges_paisa'];
  if not me.on_tea then hide := hide || array['cups', 'guest_cups', 'tea_paisa']; end if;
  if not me.on_kitchen then hide := hide || array['kitchen_days', 'kitchen_shared_paisa']; end if;
  select coalesce(jsonb_agg(case when r ->> 'member_id' = me.id::text then r else r - hide end), '[]'::jsonb)
    into rows_ from jsonb_array_elements(coalesce(j -> 'rows', '[]'::jsonb)) r;
  j := (j - 'parties_paisa') || jsonb_build_object('rows', rows_);
  if not me.on_tea then j := j - 'tea'; end if;
  if not me.on_kitchen then j := j - 'kitchen';
  elsif j ? 'kitchen' then j := jsonb_set(j, '{kitchen}', (j -> 'kitchen') - 'meals_paisa');
  end if;
  return j || jsonb_build_object('sees', jsonb_build_object('tea', me.on_tea, 'kitchen', me.on_kitchen, 'meals', false, 'everyone', false));
end $$;

-- ---------------------------------------------------------------- notifications about an entry
-- Same as before, plus: a notification that names an entry (data.entry_type + data.entry_id) goes only to the
-- members who can see that entry.
create or replace function messwise.notify(p_place uuid, p_to text, p_kind text, p_title text, p_body text, p_data jsonb default '{}'::jsonb, p_self boolean default false)
returns void language plpgsql as $$
declare
  et text := p_data ->> 'entry_type';
  eid uuid := nullif(p_data ->> 'entry_id', '')::uuid;
begin
  insert into messwise.notifications(user_id, place_id, kind, title, body, data)
  select distinct m.user_id, p_place, p_kind, p_title, coalesce(p_body, ''), coalesce(p_data, '{}'::jsonb) || jsonb_build_object('place_id', p_place)
  from messwise.members m
  where m.place_id = p_place and m.user_id is not null and m.status = 'active'
    and (p_self or m.user_id is distinct from auth.uid())
    and (case when p_to = 'all' then true
              when p_to = 'admins' then m.role in ('owner','manager')
              else m.id::text = p_to end)
    and (et is null or eid is null or messwise.sees_entry(m, et, eid));
end $$;

-- ---------------------------------------------------------------- entries
create or replace function public.mw_entries(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  m1 date := messwise.month_end(m0);
begin
  return jsonb_build_object('month', m0, 'items', coalesce((select jsonb_agg(x order by x ->> 'day' desc, x ->> 'created_at' desc) from (
      select messwise.purchase_json(pu) x from messwise.purchases pu
       where pu.place_id = p_place and pu.bought_on between m0 and m1 and messwise.sees_entry(me, 'purchase', pu.id)
      union all
      select messwise.meal_json(ml) from messwise.meals ml
       where ml.place_id = p_place and ml.day between m0 and m1 and messwise.sees_entry(me, 'meal', ml.id)
      union all
      select messwise.party_json(pt) from messwise.parties pt
       where pt.place_id = p_place and pt.day between m0 and m1 and messwise.sees_entry(me, 'party', pt.id)
    ) s), '[]'::jsonb));
end $$;

create or replace function public.mw_entry_get(p_type text, p_id uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid;
  me messwise.members;
  e jsonb;
begin
  if p_type = 'purchase' then select place_id, messwise.purchase_json(x) into pl, e from messwise.purchases x where id = p_id;
  elsif p_type = 'meal' then select place_id, messwise.meal_json(x) into pl, e from messwise.meals x where id = p_id;
  elsif p_type = 'party' then select place_id, messwise.party_json(x) into pl, e from messwise.parties x where id = p_id;
  else perform messwise.err('INVALID', 'unknown entry');
  end if;
  if pl is null then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
  me := messwise.me_in(pl);
  if not messwise.sees_entry(me, p_type, p_id) then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
  return e || jsonb_build_object(
    'versions', coalesce((select jsonb_agg(jsonb_build_object('version', v.version, 'reason', v.reason, 'changed_at', v.changed_at,
        'changed_by_name', messwise.creator_name(pl, v.changed_by), 'snapshot', v.snapshot) order by v.id desc)
      from messwise.entry_versions v where v.entry_type = p_type and v.entry_id = p_id), '[]'::jsonb),
    'questions', coalesce((select jsonb_agg(jsonb_build_object('id', q.id, 'text', q.text, 'status', q.status, 'answer', q.answer,
        'asked_by', m.display_name, 'created_at', q.created_at) order by q.created_at)
      from messwise.questions q join messwise.members m on m.id = q.member_id
      where q.entity = p_type and q.entity_id = p_id and (messwise.is_admin(me) or q.member_id = me.id)), '[]'::jsonb),
    'can_edit', messwise.is_admin(me));
end $$;

create or replace function public.mw_question_ask(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.me_in(pl);
  q messwise.questions;
  mon date := nullif(p ->> 'month', '')::date;
  tally jsonb;
  ent text := coalesce(p ->> 'entity', '');
  eid uuid := nullif(p ->> 'entity_id', '')::uuid;
begin
  if ent not in ('purchase','meal','party','tea','bill','other') then perform messwise.err('INVALID', 'unknown subject'); end if;
  if ent in ('purchase','meal','party') and eid is not null and not messwise.sees_entry(me, ent, eid) then
    perform messwise.err('NOT_FOUND', 'entry not found');
  end if;
  if coalesce((p ->> 'share_tally')::boolean, false) then
    tally := public.mw_my_tea(pl, coalesce(mon, messwise.today()));
  end if;
  insert into messwise.questions(place_id, member_id, entity, entity_id, month, text, tally)
  values (pl, me.id, ent, eid, case when mon is not null then messwise.month_of(mon) end, trim(p ->> 'text'), tally)
  returning * into q;
  perform messwise.notify(pl, 'admins', 'question', 'Question from ' || me.display_name, left(q.text, 140), jsonb_build_object('question_id', q.id));
  return jsonb_build_object('id', q.id, 'status', q.status);
end $$;

-- History: entry lines only for entries the viewer can see; month and money lines only for the managers.
create or replace function public.mw_activity(p_place uuid, p_before bigint) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare me messwise.members := messwise.me_in(p_place);
begin
  return coalesce((select jsonb_agg(jsonb_build_object('id', a.id, 'actor_name', a.actor_name, 'action', a.action, 'entity', a.entity,
      'entity_id', a.entity_id, 'summary', a.summary, 'at', a.at) order by a.id desc)
    from (select * from messwise.activity x
           where x.place_id = p_place and (p_before is null or x.id < p_before)
             and (messwise.is_admin(me)
                  or (x.entity in ('purchase','meal','party') and x.entity_id is not null and messwise.sees_entry(me, x.entity, x.entity_id))
                  or (x.entity = 'tea' and me.on_tea)
                  or x.entity in ('place', 'member'))
           order by x.id desc limit 60) a), '[]'::jsonb);
end $$;

-- ---------------------------------------------------------------- months, bills, dashboard
create or replace function public.mw_month(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  mo messwise.months;
  calc jsonb;
begin
  if m0 > messwise.month_of(messwise.today()) then perform messwise.err('INVALID', 'that month has not started'); end if;
  select * into mo from messwise.months where place_id = p_place and month = m0;
  if found and mo.status = 'billed' then
    calc := mo.summary;
  else
    calc := messwise.month_calc(p_place, m0, messwise.today());
  end if;
  return messwise.month_view(me, calc) || jsonb_build_object(
    'status', coalesce(mo.status, 'open'), 'billed_at', mo.billed_at, 'reopen_reason', mo.reopen_reason,
    'can_bill', messwise.is_admin(me) and coalesce(mo.status, 'open') = 'open',
    'can_reopen', me.role = 'owner' and mo.status = 'billed',
    'bills', coalesce((select jsonb_agg(messwise.bill_for(me, b) order by m.join_order) from messwise.bills b join messwise.members m on m.id = b.member_id
                        where b.place_id = p_place and b.kind = 'month' and b.month = m0), '[]'::jsonb));
end $$;

create or replace function public.mw_bills(p_place uuid, p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  mon date := nullif(p ->> 'month', '')::date;
  unpaid boolean := coalesce((p ->> 'unpaid')::boolean, false);
  mem uuid := nullif(p ->> 'member_id', '')::uuid;
begin
  return coalesce((select jsonb_agg(x order by x ->> 'month' desc, x ->> 'member_name') from (
      select messwise.bill_for(me, b) x from messwise.bills b
      where b.place_id = p_place and (mon is null or b.month = messwise.month_of(mon)) and (mem is null or b.member_id = mem)) s
    where not unpaid or (x ->> 'remaining_paisa')::bigint > 0), '[]'::jsonb);
end $$;

create or replace function public.mw_bill_get(p_bill uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  b messwise.bills;
  me messwise.members;
  m0 date; m1 date;
  own boolean;
  full_ boolean;
begin
  select * into b from messwise.bills where id = p_bill;
  if not found then perform messwise.err('NOT_FOUND', 'bill not found'); end if;
  me := messwise.me_in(b.place_id);
  own := me.id = b.member_id;
  full_ := own or messwise.is_admin(me);
  m0 := b.month; m1 := messwise.month_end(b.month);
  return messwise.bill_for(me, b) || jsonb_build_object(
    'is_mine', own, 'can_mark', messwise.is_admin(me) and (not own or (messwise.can_json(me) ->> 'sole_admin')::boolean),
    'meals', case when b.kind = 'month' and full_ then coalesce((select jsonb_agg(jsonb_build_object('id', ml.id, 'day', ml.day, 'kind', ml.kind, 'title', ml.title, 'share_paisa', s.share_paisa) order by ml.day)
        from messwise.entry_shares s join messwise.meals ml on ml.id = s.entry_id
        where s.entry_type = 'meal' and s.member_id = b.member_id and ml.status = 'active' and ml.day between m0 and m1), '[]'::jsonb) end,
    'paid_for_group', case when full_ then coalesce((select jsonb_agg(x) from (
        select jsonb_build_object('type', 'purchase', 'id', pu.id, 'title', pu.item, 'bill', pu.bill, 'day', pu.bought_on, 'amount_paisa', ep.amount_paisa) x
          from messwise.entry_payers ep join messwise.purchases pu on pu.id = ep.entry_id
         where b.kind = 'month' and ep.entry_type = 'purchase' and ep.member_id = b.member_id and pu.status = 'active' and pu.bought_on between m0 and m1
        union all
        select jsonb_build_object('type', 'meal', 'id', ml.id, 'title', initcap(ml.kind), 'day', ml.day, 'amount_paisa', ep.amount_paisa)
          from messwise.entry_payers ep join messwise.meals ml on ml.id = ep.entry_id
         where b.kind = 'month' and ep.entry_type = 'meal' and ep.member_id = b.member_id and ml.status = 'active' and ml.day between m0 and m1
        union all
        select jsonb_build_object('type', 'party', 'id', pt.id, 'title', pt.title, 'day', pt.day, 'amount_paisa', ep.amount_paisa)
          from messwise.entry_payers ep join messwise.parties pt on pt.id = ep.entry_id
         where ep.entry_type = 'party' and ep.member_id = b.member_id and pt.status = 'active'
           and ((b.kind = 'party' and pt.id = b.party_id) or (b.kind = 'month' and pt.add_to_month and pt.day between m0 and m1))
      ) s), '[]'::jsonb) else '[]'::jsonb end,
    'payments', coalesce((select jsonb_agg(jsonb_build_object('id', py.id, 'direction', py.direction, 'amount_paisa', py.amount_paisa, 'method', py.method,
        'slip_path', case when messwise.is_admin(me) or own then py.slip_path end, 'has_slip', py.slip_path is not null,
        'note', py.note, 'status', py.status, 'reason', py.reason, 'created_at', py.created_at, 'decided_at', py.decided_at,
        'decided_by_name', messwise.creator_name(b.place_id, py.decided_by)) order by py.created_at desc)
      from messwise.payments py where py.bill_id = b.id), '[]'::jsonb));
end $$;

create or replace function public.mw_member_get(p_member uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  m messwise.members;
  me messwise.members;
  mon date := messwise.month_of(coalesce(p_month, messwise.today()));
  calc jsonb;
begin
  select * into m from messwise.members where id = p_member;
  if not found then perform messwise.err('NOT_FOUND', 'member not found'); end if;
  me := messwise.me_in(m.place_id);
  calc := messwise.month_view(me, messwise.month_calc(m.place_id, mon, messwise.today()));
  return messwise.member_json(m) || jsonb_build_object(
    'month', mon,
    'month_row', (select r from jsonb_array_elements(calc -> 'rows') r where r ->> 'member_id' = m.id::text),
    'leaves', coalesce((select jsonb_agg(jsonb_build_object('id', l.id, 'from_date', l.from_date, 'to_date', l.to_date, 'note', l.note) order by l.from_date desc)
                         from messwise.leaves l where l.member_id = m.id and l.status = 'active'), '[]'::jsonb),
    'bills', coalesce((select jsonb_agg(messwise.bill_for(me, b) order by b.month desc, b.kind) from messwise.bills b where b.member_id = m.id), '[]'::jsonb));
end $$;

create or replace function public.mw_dashboard(p_place uuid) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  pl messwise.places := messwise.place(p_place);
  d date := messwise.today();
  calc jsonb := messwise.month_calc(p_place, messwise.month_of(d), d);
  view_ jsonb := messwise.month_view(me, calc);
  admin boolean := messwise.is_admin(me);
begin
  return jsonb_build_object(
    'place', messwise.place_json(pl, me),
    'today', d,
    'month', jsonb_strip_nulls(jsonb_build_object('month', view_ -> 'month', 'status', view_ -> 'status', 'tea', view_ -> 'tea', 'kitchen', view_ -> 'kitchen',
                                                  'warnings', view_ -> 'warnings', 'sees', view_ -> 'sees')),
    'my_so_far', (select r from jsonb_array_elements(calc -> 'rows') r where r ->> 'member_id' = me.id::text),
    'my_bills', coalesce((select jsonb_agg(x order by x ->> 'month' desc) from (
        select messwise.bill_json(b) x from messwise.bills b where b.member_id = me.id) s
      where (x ->> 'remaining_paisa')::bigint <> 0), '[]'::jsonb),
    'advance_paisa', (select coalesce(sum(amount_paisa), 0) from messwise.payments where member_id = me.id and bill_id is null and direction = 'in' and status = 'accepted'),
    'pending', coalesce((select jsonb_agg(x order by (x ->> 'member_id') = me.id::text desc, x ->> 'month' desc, x ->> 'member_name') from (
        select messwise.bill_for(me, b) x from messwise.bills b where b.place_id = p_place and b.total_paisa > 0) s
      where (x ->> 'remaining_paisa')::bigint > 0), '[]'::jsonb),
    'tea_today', jsonb_build_object(
      'official', (select coalesce(sum(cups), 0) from messwise.tea_counts where place_id = p_place and day = d),
      'my_official', (select coalesce(sum(cups), 0) from messwise.tea_counts where place_id = p_place and day = d and subject = 'member' and member_id = me.id),
      'my_marks', (select coalesce(sum(cups), 0) from messwise.my_tea_marks where user_id = me.user_id and place_id = p_place and day = d)),
    'meals_today', (select count(*) from messwise.meals ml where ml.place_id = p_place and ml.day = d and ml.status = 'active' and messwise.sees_entry(me, 'meal', ml.id)),
    'on_leave_today', coalesce((select jsonb_agg(m.display_name order by m.join_order) from messwise.members m
                                where m.place_id = p_place and m.status = 'active' and messwise.on_leave(m.id, d)), '[]'::jsonb),
    'admin', case when admin then jsonb_build_object(
      'slips_waiting', (select count(*) from messwise.payments where place_id = p_place and status = 'sent'),
      'open_questions', (select count(*) from messwise.questions where place_id = p_place and status = 'open'),
      'join_requests', (select count(*) from messwise.join_requests where place_id = p_place and status = 'pending'),
      'unbilled_months', coalesce((select jsonb_agg(mm order by mm) from (
          select distinct messwise.month_of(x.day) mm from (
            select bought_on as day from messwise.purchases where place_id = p_place and status = 'active'
            union all select day from messwise.meals where place_id = p_place and status = 'active'
            union all select day from messwise.tea_counts where place_id = p_place) x
          where messwise.month_of(x.day) < messwise.month_of(d)
            and not messwise.month_billed(p_place, x.day)) y), '[]'::jsonb)) end,
    'unread', (select count(*) from messwise.notifications where user_id = me.user_id and read_at is null));
end $$;

-- ---------------------------------------------------------------- reports
create or replace function public.mw_report(p_place uuid, p_month date) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  me messwise.members := messwise.me_in(p_place);
  pl messwise.places := messwise.place(p_place);
  m0 date := messwise.month_of(coalesce(p_month, messwise.today()));
  m1 date := messwise.month_end(m0);
  mon jsonb := public.mw_month(p_place, m0);
  admin boolean := messwise.is_admin(me);
begin
  return jsonb_build_object(
    'place', jsonb_build_object('name', pl.name, 'address', pl.address, 'kind', pl.kind),
    'generated_at', now(), 'generated_by', me.display_name, 'me_member_id', me.id,
    'month', mon,
    'tea_purchases', case when admin or me.on_tea then coalesce((select jsonb_agg(messwise.purchase_json(pu) order by pu.bought_on, pu.created_at)
        from messwise.purchases pu where pu.place_id = p_place and pu.bill = 'tea' and pu.status = 'active' and pu.bought_on between m0 and m1), '[]'::jsonb)
      else '[]'::jsonb end,
    'kitchen_purchases', case when admin or me.on_kitchen then coalesce((select jsonb_agg(messwise.purchase_json(pu) order by pu.bought_on, pu.created_at)
        from messwise.purchases pu where pu.place_id = p_place and pu.bill = 'kitchen' and pu.status = 'active' and pu.bought_on between m0 and m1), '[]'::jsonb)
      else '[]'::jsonb end,
    'meals', coalesce((select jsonb_agg(messwise.meal_json(ml) order by ml.day, ml.created_at)
        from messwise.meals ml where ml.place_id = p_place and ml.status = 'active' and ml.day between m0 and m1
         and messwise.sees_entry(me, 'meal', ml.id)), '[]'::jsonb),
    'parties', coalesce((select jsonb_agg(messwise.party_json(pt) order by pt.day)
        from messwise.parties pt where pt.place_id = p_place and pt.status = 'active' and pt.day between m0 and m1
         and messwise.sees_entry(me, 'party', pt.id)), '[]'::jsonb),
    'tea_days', case when admin or me.on_tea then coalesce((select jsonb_agg(jsonb_build_object('day', t.day, 'subject', t.subject, 'member_id', t.member_id, 'cups', t.cups) order by t.day)
        from messwise.tea_counts t where t.place_id = p_place and t.day between m0 and m1), '[]'::jsonb)
      else '[]'::jsonb end);
end $$;

-- ================= supabase/migrations/0006_manager_money_and_walk_ins.sql
-- MessWise 0006 (Atif, 27 Nov 2026).
--
--  * Money is the Canteen Manager's job. Purchases, tea counts, meals, parties, final bills, payment slips,
--    cash, reminders and reopening a billed month are for Canteen Managers only (the main one and deputies).
--    The owner does these only while they are also the main Canteen Manager (as when a place is new and
--    nobody else has joined). The owner keeps members, join requests, roles, leave, notices and place
--    settings, and still sees everything.
--  * Parties can include non-members, who pay their share on the spot. The party total is divided on
--    everyone who attended (members, guests and non-members). Non-members' cash counts as handed to whoever
--    paid the party bill, so it comes off what the group owes them; the party still adds up exactly.
--
-- Safe to run more than once.

-- ---------------------------------------------------------------- who is a Canteen Manager

create or replace function messwise.is_manager(m messwise.members) returns boolean language sql immutable as $$
  select m.status = 'active' and (m.role = 'manager' or (m.role = 'owner' and m.is_main_manager))
$$;

create or replace function messwise.require_manager(p_place uuid) returns messwise.members language plpgsql stable as $$
declare m messwise.members := messwise.me_in(p_place);
begin
  if not messwise.is_manager(m) then perform messwise.err('FORBIDDEN', 'only the Canteen Manager can do this'); end if;
  return m;
end $$;

-- manage: members, roles, leave, notices (owner and managers). money: entries, bills and payments.
create or replace function messwise.can_json(m messwise.members) returns jsonb language plpgsql stable as $$
declare admins int; managers int;
begin
  select count(*) filter (where x.role in ('owner','manager')), count(*) filter (where messwise.is_manager(x))
    into admins, managers from messwise.members x where x.place_id = m.place_id and x.status = 'active';
  return jsonb_build_object('manage', messwise.is_admin(m), 'owner', m.role = 'owner', 'money', messwise.is_manager(m),
    'sole_admin', messwise.is_admin(m) and admins = 1, 'sole_manager', messwise.is_manager(m) and managers = 1);
end $$;

create or replace function messwise.check_can_mark(me messwise.members, p_member uuid) returns void language plpgsql stable as $$
begin
  if not messwise.is_manager(me) then perform messwise.err('FORBIDDEN', 'only the Canteen Manager can mark payments'); end if;
  if p_member = me.id and not (messwise.can_json(me) ->> 'sole_manager')::boolean then
    perform messwise.err('FORBIDDEN', 'your own bill must be marked by another Canteen Manager');
  end if;
end $$;

-- 'managers' reaches only those who handle money (slips to accept, for example).
create or replace function messwise.notify(p_place uuid, p_to text, p_kind text, p_title text, p_body text, p_data jsonb default '{}'::jsonb, p_self boolean default false)
returns void language plpgsql as $$
declare
  et text := p_data ->> 'entry_type';
  eid uuid := nullif(p_data ->> 'entry_id', '')::uuid;
begin
  insert into messwise.notifications(user_id, place_id, kind, title, body, data)
  select distinct m.user_id, p_place, p_kind, p_title, coalesce(p_body, ''), coalesce(p_data, '{}'::jsonb) || jsonb_build_object('place_id', p_place)
  from messwise.members m
  where m.place_id = p_place and m.user_id is not null and m.status = 'active'
    and (p_self or m.user_id is distinct from auth.uid())
    and (case when p_to = 'all' then true
              when p_to = 'admins' then m.role in ('owner','manager')
              when p_to = 'managers' then messwise.is_manager(m)
              else m.id::text = p_to end)
    and (et is null or eid is null or messwise.sees_entry(m, et, eid));
end $$;

-- Replaces one exact piece of a function's code (p_times copies of it), so the rest stays as tested.
-- Already-replaced functions are left alone.
create or replace function messwise.swap_in_function(p_name text, p_from text, p_to text, p_times int default 1)
returns void language plpgsql as $$
declare fn oid; def text; n int;
begin
  select p.oid into strict fn from pg_proc p join pg_namespace s on s.oid = p.pronamespace where s.nspname || '.' || p.proname = p_name;
  def := pg_get_functiondef(fn);
  n := (length(def) - length(replace(def, p_from, ''))) / length(p_from);
  if n = 0 and position(p_to in def) > 0 then return; end if;
  if n <> p_times then raise exception '% has % copies of [%], expected %', p_name, n, p_from, p_times; end if;
  execute replace(def, p_from, p_to);
end $$;

do $$
declare f text;
begin
  foreach f in array array['public.mw_purchase_save', 'public.mw_purchase_void', 'public.mw_tea_set', 'public.mw_tea_round',
                           'public.mw_meal_save', 'public.mw_meal_void', 'public.mw_party_void', 'public.mw_month_bill',
                           'public.mw_payments_waiting', 'public.mw_remind'] loop
    perform messwise.swap_in_function(f, 'messwise.require_admin(', 'messwise.require_manager(');
  end loop;
  perform messwise.swap_in_function('public.mw_month_reopen', 'messwise.require_owner(p_place)', 'messwise.require_manager(p_place)');
  perform messwise.swap_in_function('public.mw_tea_day', '''can_edit'', messwise.is_admin(me) and not', '''can_edit'', messwise.is_manager(me) and not');
  perform messwise.swap_in_function('public.mw_entry_get', '''can_edit'', messwise.is_admin(me));', '''can_edit'', messwise.is_manager(me));');
  perform messwise.swap_in_function('public.mw_month', '''can_bill'', messwise.is_admin(me) and', '''can_bill'', messwise.is_manager(me) and');
  perform messwise.swap_in_function('public.mw_bill_get',
    '''can_mark'', messwise.is_admin(me) and (not own or (messwise.can_json(me) ->> ''sole_admin'')::boolean)',
    '''can_mark'', messwise.is_manager(me) and (not own or (messwise.can_json(me) ->> ''sole_manager'')::boolean)');
  perform messwise.swap_in_function('public.mw_payment_send', 'messwise.notify(pl, ''admins'', ''slip'',', 'messwise.notify(pl, ''managers'', ''slip'',');
end $$;

-- ---------------------------------------------------------------- non-members at parties

alter table messwise.party_people add column if not exists name text;
alter table messwise.party_people drop constraint if exists party_people_name_check;
alter table messwise.party_people add constraint party_people_name_check check (name is null or length(trim(name)) between 1 and 60);
alter table messwise.party_people drop constraint if exists party_people_subject_check;
alter table messwise.party_people add constraint party_people_subject_check check (subject in ('member','personal_guest','office_guest','walk_in'));
alter table messwise.party_people drop constraint if exists party_people_check;
alter table messwise.party_people add constraint party_people_check check ((subject in ('office_guest','walk_in')) = (member_id is null));

-- What each non-member paid on the spot, and all of it together.
alter table messwise.parties add column if not exists walk_in_each_paisa bigint not null default 0;
alter table messwise.parties add column if not exists walk_in_paisa bigint not null default 0;
alter table messwise.parties drop constraint if exists parties_walk_in_check;
alter table messwise.parties add constraint parties_walk_in_check check (walk_in_each_paisa >= 0 and walk_in_paisa >= 0 and walk_in_paisa < total_paisa);

-- Non-members' cash handed to a payer of the party; it comes off what the group owes that payer.
alter table messwise.entry_payers add column if not exists received_paisa bigint not null default 0;
alter table messwise.entry_payers drop constraint if exists entry_payers_received_check;
alter table messwise.entry_payers add constraint entry_payers_received_check check (received_paisa >= 0 and received_paisa <= amount_paisa);

do $$
begin
  perform messwise.swap_in_function('messwise.month_calc',
    'sum(ep.amount_paisa) v from messwise.entry_payers ep', 'sum(ep.amount_paisa - ep.received_paisa) v from messwise.entry_payers ep');
  perform messwise.swap_in_function('messwise.party_bills',
    'coalesce((select amount_paisa from messwise.entry_payers ep where', 'coalesce((select amount_paisa - ep.received_paisa from messwise.entry_payers ep where');
  perform messwise.swap_in_function('public.mw_bill_get',
    '''day'', pt.day, ''amount_paisa'', ep.amount_paisa)', '''day'', pt.day, ''amount_paisa'', ep.amount_paisa, ''received_paisa'', ep.received_paisa)');
end $$;

drop function messwise.swap_in_function(text, text, text, int);

-- Each non-member pays the share per head (whole rupees when the place rounds to rupees).
create or replace function messwise.party_walk_in(p_party uuid) returns void language plpgsql as $$
declare pt messwise.parties; pl messwise.places; all_heads int; walk int; each_ bigint := 0;
begin
  select * into pt from messwise.parties where id = p_party;
  select * into pl from messwise.places where id = pt.place_id;
  select coalesce(sum(heads), 0), coalesce(sum(heads) filter (where subject = 'walk_in'), 0) into all_heads, walk
    from messwise.party_people where party_id = p_party;
  if walk > 0 then
    if walk >= all_heads then perform messwise.err('INVALID', 'add at least one member who attended'); end if;
    each_ := case when pl.round_to_rupee then round(pt.total_paisa::numeric / all_heads / 100) * 100
                  else round(pt.total_paisa::numeric / all_heads) end;
    if each_ * walk >= pt.total_paisa then perform messwise.err('INVALID', 'non-members'' shares would cover the whole party'); end if;
  end if;
  update messwise.parties set walk_in_each_paisa = each_, walk_in_paisa = each_ * walk where id = p_party;
end $$;

-- Party: share per head = total / everyone who attended. Non-members pay theirs on the spot; the rest is
-- divided as before: personal guests go to their host; office guests are divided on all members present
-- that day (office_split 'all') or only on attendees.
create or replace function messwise.party_shares(p_party uuid) returns jsonb language plpgsql stable as $$
declare
  pt messwise.parties;
  pl messwise.places;
  split_set uuid[];
  n_split int;
  office_heads int;
  items jsonb;
begin
  select * into pt from messwise.parties where id = p_party;
  select * into pl from messwise.places where id = pt.place_id;
  select coalesce(sum(heads), 0) into office_heads from messwise.party_people where party_id = p_party and subject = 'office_guest';
  if pt.office_split = 'attendees' then
    select coalesce(array_agg(distinct member_id), '{}') into split_set from messwise.party_people where party_id = p_party and subject = 'member';
  else
    -- Everyone present that day, plus attending members even if marked away.
    split_set := array(select distinct x from unnest(
      messwise.present_ids(pt.place_id, pt.day) ||
      coalesce((select array_agg(member_id) from messwise.party_people where party_id = p_party and subject = 'member'), '{}'::uuid[])) x);
  end if;
  n_split := coalesce(array_length(split_set, 1), 0);
  if office_heads > 0 and n_split = 0 then perform messwise.err('STATE', 'office guests need at least one member to share their part'); end if;

  select jsonb_agg(jsonb_build_object('k', k, 'w', w, 'o', o)) into items from (
    select x.member_id::text k, sum(x.w) w, min(x.o) o from (
      select pp.member_id, (pp.heads * greatest(n_split, 1))::numeric as w, m.join_order as o
        from messwise.party_people pp join messwise.members m on m.id = pp.member_id
       where pp.party_id = p_party and pp.subject in ('member','personal_guest')
      union all
      select m.id, office_heads::numeric, m.join_order from messwise.members m where m.id = any(split_set) and office_heads > 0
    ) x group by x.member_id
  ) y;
  if items is null then perform messwise.err('INVALID', 'add at least one member who attended'); end if;
  return messwise.alloc(pt.total_paisa - pt.walk_in_paisa, items, pl.round_to_rupee);
end $$;

-- Splits the non-members' cash over the party's payers in proportion to what each paid.
create or replace function messwise.party_received(p_party uuid) returns void language plpgsql as $$
declare pt messwise.parties; pl messwise.places; items jsonb; a jsonb;
begin
  select * into pt from messwise.parties where id = p_party;
  select * into pl from messwise.places where id = pt.place_id;
  update messwise.entry_payers set received_paisa = 0 where entry_type = 'party' and entry_id = p_party;
  if pt.walk_in_paisa = 0 then return; end if;
  select jsonb_agg(jsonb_build_object('k', ep.member_id::text, 'w', ep.amount_paisa, 'o', m.join_order)) into items
    from messwise.entry_payers ep join messwise.members m on m.id = ep.member_id
   where ep.entry_type = 'party' and ep.entry_id = p_party;
  a := messwise.alloc(pt.walk_in_paisa, items, pl.round_to_rupee);
  if exists (select 1 from messwise.entry_payers ep where ep.entry_type = 'party' and ep.entry_id = p_party
              and coalesce((a ->> ep.member_id::text)::bigint, 0) > ep.amount_paisa) then
    perform messwise.err('INVALID', 'a payer paid too little to take the non-members'' cash; change who paid');
  end if;
  update messwise.entry_payers ep set received_paisa = coalesce((a ->> ep.member_id::text)::bigint, 0)
   where ep.entry_type = 'party' and ep.entry_id = p_party;
end $$;

create or replace function messwise.payers_json(p_type text, p_id uuid) returns jsonb language sql stable as $$
  select coalesce(jsonb_agg(jsonb_build_object('member_id', ep.member_id, 'name', m.display_name, 'amount_paisa', ep.amount_paisa,
                                               'received_paisa', ep.received_paisa) order by m.join_order), '[]'::jsonb)
  from messwise.entry_payers ep join messwise.members m on m.id = ep.member_id
  where ep.entry_type = p_type and ep.entry_id = p_id
$$;

create or replace function messwise.party_json(pt messwise.parties) returns jsonb language plpgsql stable as $$
declare heads int; walk int; bills jsonb;
begin
  select coalesce(sum(p.heads), 0), coalesce(sum(p.heads) filter (where p.subject = 'walk_in'), 0) into heads, walk
    from messwise.party_people p where p.party_id = pt.id;
  select coalesce(jsonb_agg(messwise.bill_json(b) order by m.join_order), '[]'::jsonb) into bills
    from messwise.bills b join messwise.members m on m.id = b.member_id where b.party_id = pt.id;
  return jsonb_build_object('type', 'party', 'id', pt.id, 'place_id', pt.place_id, 'title', pt.title,
    'amount_paisa', pt.total_paisa, 'day', pt.day, 'office_split', pt.office_split, 'add_to_month', pt.add_to_month,
    'note', pt.note, 'receipt_path', pt.receipt_path, 'status', pt.status, 'void_reason', pt.void_reason, 'version', pt.version,
    'created_by_name', messwise.creator_name(pt.place_id, pt.created_by), 'created_at', pt.created_at, 'updated_at', pt.updated_at,
    'heads', heads, 'per_head_paisa', case when heads > 0 then round(pt.total_paisa::numeric / heads) end,
    'walk_in_heads', walk, 'walk_in_each_paisa', pt.walk_in_each_paisa, 'walk_in_paisa', pt.walk_in_paisa,
    'people', coalesce((select jsonb_agg(jsonb_build_object('subject', pp.subject, 'member_id', pp.member_id,
                                                            'name', coalesce(m.display_name, pp.name), 'heads', pp.heads))
                          from messwise.party_people pp left join messwise.members m on m.id = pp.member_id where pp.party_id = pt.id), '[]'::jsonb),
    'payers', messwise.payers_json('party', pt.id), 'shares', messwise.shares_json('party', pt.id), 'bills', bills);
end $$;

-- people: [{subject: member|personal_guest|office_guest|walk_in, member_id, heads, name}]
-- walk_in: a non-member who pays their share on the spot (name optional, heads = how many came).
create or replace function public.mw_party_save(p jsonb) returns jsonb language plpgsql security definer
set search_path = messwise, extensions, pg_temp as $$
declare
  pl uuid := (p ->> 'place_id')::uuid;
  me messwise.members := messwise.require_manager(pl);
  place_ messwise.places := messwise.place(pl);
  ptid uuid := nullif(p ->> 'id', '')::uuid;
  cur messwise.parties;
  pt messwise.parties;
  amt bigint := (p ->> 'amount_paisa')::bigint;
  d date := (p ->> 'day')::date;
  split text := coalesce(p ->> 'office_split', place_.party_office_split);
  to_month boolean := coalesce((p ->> 'add_to_month')::boolean, false);
  payers jsonb;
  old_sh jsonb := '{}'::jsonb;
  new_sh jsonb;
  why text;
  r record;
begin
  if length(trim(coalesce(p ->> 'title', ''))) = 0 then perform messwise.err('INVALID', 'give the party a name'); end if;
  if amt is null or amt <= 0 then perform messwise.err('INVALID', 'amount must be above zero'); end if;
  if split not in ('all','attendees') then perform messwise.err('INVALID', 'unknown split'); end if;
  if jsonb_typeof(p -> 'people') <> 'array' or jsonb_array_length(p -> 'people') = 0 then perform messwise.err('INVALID', 'add who attended'); end if;
  if d is null or d > messwise.today() + 60 then perform messwise.err('INVALID', 'choose the party date'); end if;
  if to_month then perform messwise.require_open(pl, d); end if;
  payers := messwise.clean_payers(pl, p -> 'payers', amt, me);
  if ptid is null then
    insert into messwise.parties(place_id, day, title, total_paisa, office_split, add_to_month, note, receipt_path, created_by)
    values (pl, d, trim(p ->> 'title'), amt, split, to_month, trim(coalesce(p ->> 'note', '')), nullif(p ->> 'receipt_path', ''), me.user_id)
    returning * into pt;
  else
    select * into cur from messwise.parties where id = ptid and place_id = pl for update;
    if not found then perform messwise.err('NOT_FOUND', 'entry not found'); end if;
    if cur.status = 'void' then perform messwise.err('STATE', 'this entry was voided'); end if;
    if cur.add_to_month then perform messwise.require_open(pl, cur.day); end if;
    why := messwise.need_reason(p ->> 'reason');
    select coalesce(jsonb_object_agg(member_id::text, share_paisa), '{}'::jsonb) into old_sh from messwise.entry_shares where entry_type = 'party' and entry_id = ptid;
    insert into messwise.entry_versions(entry_type, entry_id, version, snapshot, reason, changed_by)
    values ('party', cur.id, cur.version, messwise.party_json(cur), why, me.user_id);
    update messwise.parties set day = d, title = trim(p ->> 'title'), total_paisa = amt, office_split = split, add_to_month = to_month,
      note = trim(coalesce(p ->> 'note', '')), receipt_path = coalesce(nullif(p ->> 'receipt_path', ''), receipt_path),
      walk_in_each_paisa = 0, walk_in_paisa = 0,
      version = version + 1, updated_by = me.user_id, updated_at = now()
    where id = ptid returning * into pt;
    delete from messwise.party_people where party_id = pt.id;
  end if;
  for r in select i ->> 'subject' subj, nullif(i ->> 'member_id', '')::uuid mem, coalesce((i ->> 'heads')::int, 1) h,
                  nullif(trim(coalesce(i ->> 'name', '')), '') nm
           from jsonb_array_elements(p -> 'people') i loop
    if r.subj not in ('member','personal_guest','office_guest','walk_in') then perform messwise.err('INVALID', 'unknown person'); end if;
    if r.subj in ('office_guest','walk_in') then r.mem := null;
    elsif r.mem is null then perform messwise.err('INVALID', 'choose the member');
    else perform messwise.member_of(pl, r.mem); end if;
    if r.subj = 'member' then r.h := 1; end if;
    insert into messwise.party_people(party_id, subject, member_id, heads, name)
    values (pt.id, r.subj, r.mem, r.h, case when r.subj = 'walk_in' then left(r.nm, 60) end);
  end loop;
  if (select count(*) from messwise.party_people where party_id = pt.id and subject = 'member') <>
     (select count(distinct member_id) from messwise.party_people where party_id = pt.id and subject = 'member') then
    perform messwise.err('DUPLICATE', 'a member is listed twice');
  end if;
  perform messwise.party_walk_in(pt.id);
  select * into pt from messwise.parties where id = pt.id;
  new_sh := messwise.party_shares(pt.id);
  delete from messwise.entry_shares where entry_type = 'party' and entry_id = pt.id;
  insert into messwise.entry_shares(entry_type, entry_id, member_id, share_paisa)
  select 'party', pt.id, key::uuid, value::bigint from jsonb_each_text(new_sh) where value::bigint > 0;
  perform messwise.save_payers('party', pt.id, payers);
  perform messwise.party_received(pt.id);
  perform messwise.party_bills(pt.id);
  if ptid is null then
    perform messwise.log(pl, 'party_added', 'party', pt.id, pt.title || ' ' || messwise.rs(pt.total_paisa)
      || case when pt.walk_in_paisa > 0 then ', non-members paid ' || messwise.rs(pt.walk_in_paisa) || ' on the spot' else '' end);
    for r in select key k, value::bigint v from jsonb_each_text(new_sh) where value::bigint > 0 loop
      perform messwise.notify(pl, r.k, 'party', pt.title, 'Your share: ' || messwise.rs(r.v), jsonb_build_object('party_id', pt.id));
    end loop;
  else
    perform messwise.log(pl, 'party_edited', 'party', pt.id, pt.title || ': ' || messwise.rs(cur.total_paisa) || ' to ' || messwise.rs(pt.total_paisa) || ' (' || why || ')');
    perform messwise.notify_share_changes(pl, pt.title || ' edited', old_sh, new_sh, why);
  end if;
  return messwise.party_json(pt);
end $$;
