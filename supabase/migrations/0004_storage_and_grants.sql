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
