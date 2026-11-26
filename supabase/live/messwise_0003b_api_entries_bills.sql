-- Live apply, 26 Nov 2026: the part of 0003_api.sql not yet on the server (purchases to account delete), then the access rules from 0004 again.
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
revoke execute on all functions in schema messwise from public;
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
grant execute on function messwise.can_upload(text) to authenticated;
grant execute on function messwise.can_view(text) to authenticated;
