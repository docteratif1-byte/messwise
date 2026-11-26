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
