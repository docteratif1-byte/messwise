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
