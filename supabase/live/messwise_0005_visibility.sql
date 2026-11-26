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
