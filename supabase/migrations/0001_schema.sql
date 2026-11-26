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
