-- Revisa: execute uma vez em um projeto Supabase novo.
-- Nunca inclua service_role no aplicativo.
begin;

create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;
grant usage on schema public to authenticated, service_role;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique
    check (username ~ '^[a-z0-9_]{3,30}$'),
  display_name text not null
    check (char_length(btrim(display_name)) between 1 and 80),
  created_at timestamptz not null default now()
);

create table public.decks (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  exam text not null check (char_length(btrim(exam)) between 1 and 160),
  subject text not null check (char_length(btrim(subject)) between 1 and 160),
  topic text not null check (char_length(btrim(topic)) between 1 and 160),
  title text not null check (char_length(btrim(title)) between 1 and 200),
  is_public boolean not null default false,
  -- Proveniência, deliberadamente sem FK: a cópia sobrevive à remoção da origem.
  source_deck_id uuid,
  created_at timestamptz not null default now(),
  unique (owner_id, source_deck_id)
);
create index decks_library_idx on public.decks(owner_id, exam, subject, topic);
create index decks_public_idx on public.decks(owner_id) where is_public;

create table public.cards (
  id uuid primary key default gen_random_uuid(),
  deck_id uuid not null references public.decks(id) on delete cascade,
  front text not null check (char_length(btrim(front)) between 1 and 30000),
  back text not null check (char_length(btrim(back)) between 1 and 30000),
  position integer not null check (position >= 0),
  created_at timestamptz not null default now(),
  unique (deck_id, position)
);

create table public.deck_reviews (
  user_id uuid not null references public.profiles(id) on delete cascade,
  deck_id uuid not null references public.decks(id) on delete cascade,
  started_at timestamptz not null default now(),
  next_review_at timestamptz not null,
  review_count integer not null default 0 check (review_count >= 0),
  primary key (user_id, deck_id)
);
create index deck_reviews_due_idx on public.deck_reviews(user_id, next_review_at);
create index deck_reviews_deck_idx on public.deck_reviews(deck_id);

create table public.card_progress (
  user_id uuid not null references public.profiles(id) on delete cascade,
  card_id uuid not null references public.cards(id) on delete cascade,
  score integer not null default 0 check (score >= 0),
  last_answered_at timestamptz not null default now(),
  primary key (user_id, card_id)
);
create index card_progress_queue_idx
  on public.card_progress(user_id, score desc, last_answered_at, card_id)
  where score > 0;
create index card_progress_card_idx on public.card_progress(card_id);

create table public.grade_events (
  user_id uuid not null references public.profiles(id) on delete cascade,
  event_id uuid not null,
  card_id uuid not null references public.cards(id) on delete cascade,
  grade text not null check (grade in ('correct', 'wrong', 'doubt')),
  result_score integer check (result_score >= 0),
  created_at timestamptz not null default now(),
  primary key (user_id, event_id)
);
create index grade_events_card_idx on public.grade_events(card_id);

alter table public.profiles enable row level security;
alter table public.decks enable row level security;
alter table public.cards enable row level security;
alter table public.deck_reviews enable row level security;
alter table public.card_progress enable row level security;
alter table public.grade_events enable row level security;

create policy decks_read on public.decks for select to authenticated
  using (owner_id = (select auth.uid()) or is_public);
create policy decks_update on public.decks for update to authenticated
  using (owner_id = (select auth.uid()))
  with check (owner_id = (select auth.uid()));
create policy decks_delete on public.decks for delete to authenticated
  using (owner_id = (select auth.uid()));
create policy profiles_read on public.profiles for select to authenticated
  using (
    id = (select auth.uid())
    or exists (
      select 1 from public.decks d where d.owner_id = profiles.id and d.is_public
    )
  );
create policy profiles_update on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));
create policy cards_read on public.cards for select to authenticated
  using (exists (
    select 1 from public.decks d where d.id = cards.deck_id
      and (d.owner_id = (select auth.uid()) or d.is_public)
  ));
create policy deck_reviews_read on public.deck_reviews for select to authenticated
  using (user_id = (select auth.uid()));
create policy card_progress_read on public.card_progress for select to authenticated
  using (user_id = (select auth.uid()));
-- grade_events não possui política nem privilégio de leitura: acesso só pela RPC.

revoke all on table public.profiles, public.decks, public.cards,
  public.deck_reviews, public.card_progress, public.grade_events
  from public, anon, authenticated;
grant select on table public.profiles, public.decks, public.cards,
  public.deck_reviews, public.card_progress to authenticated;
grant update (username, display_name) on public.profiles to authenticated;
grant update (exam, subject, topic, title, is_public) on public.decks to authenticated;
grant delete on public.decks to authenticated;
grant all on table public.profiles, public.decks, public.cards,
  public.deck_reviews, public.card_progress, public.grade_events to service_role;

create function app_private.create_profile()
returns trigger
language plpgsql security definer set search_path = ''
as $$
declare
  v_username text := lower(btrim(coalesce(new.raw_user_meta_data ->> 'username', '')));
  v_display_name text := left(btrim(coalesce(new.raw_user_meta_data ->> 'display_name', '')), 80);
begin
  if v_username !~ '^[a-z0-9_]{3,30}$' then
    v_username := 'aluno_' || left(replace(new.id::text, '-', ''), 24);
  end if;
  if v_display_name = '' then v_display_name := 'Estudante'; end if;
  loop
    begin
      insert into public.profiles(id, username, display_name)
      values (new.id, v_username, v_display_name);
      exit;
    exception when unique_violation then
      -- Não bloqueia a criação de conta por um username já escolhido.
      v_username := 'aluno_' || left(replace(gen_random_uuid()::text, '-', ''), 24);
    end;
  end loop;
  return new;
end;
$$;
revoke all on function app_private.create_profile() from public, anon, authenticated;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function app_private.create_profile();

-- Também permite aplicar em um projeto que já tenha contas, sem expor emails.
insert into public.profiles(id, username, display_name)
select u.id, 'aluno_' || left(replace(u.id::text, '-', ''), 24), 'Estudante'
from auth.users u
where not exists (select 1 from public.profiles p where p.id = u.id);

create function app_private.save_deck(
  p_deck_id uuid,
  p_exam text,
  p_subject text,
  p_topic text,
  p_title text,
  p_is_public boolean,
  p_cards jsonb
)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
  v_id uuid;
begin
  if v_user is null then raise exception 'Entre na sua conta.' using errcode = '28000'; end if;
  if p_exam is null or char_length(btrim(p_exam)) not between 1 and 160
     or p_subject is null or char_length(btrim(p_subject)) not between 1 and 160
     or p_topic is null or char_length(btrim(p_topic)) not between 1 and 160
     or p_title is null or char_length(btrim(p_title)) not between 1 and 200
     or p_is_public is null then
    raise exception 'Preencha concurso, matéria, assunto e título válidos.' using errcode = '22023';
  end if;
  if p_cards is not null then
    if jsonb_typeof(p_cards) <> 'array' then
      raise exception 'Cartões devem ser uma lista.' using errcode = '22023';
    end if;
    if jsonb_array_length(p_cards) > 5000 then
      raise exception 'Limite de 5000 cartões por conjunto.' using errcode = '22023';
    end if;
    if exists (
      select 1 from jsonb_array_elements(p_cards) c
      where jsonb_typeof(c) <> 'object'
        or jsonb_typeof(c -> 'front') is distinct from 'string'
        or jsonb_typeof(c -> 'back') is distinct from 'string'
        or char_length(btrim(c ->> 'front')) not between 1 and 30000
        or char_length(btrim(c ->> 'back')) not between 1 and 30000
    ) then
      raise exception 'Cada cartão precisa de frente e verso de até 30000 caracteres.' using errcode = '22023';
    end if;
  end if;
  if p_deck_id is null then
    insert into public.decks(owner_id, exam, subject, topic, title, is_public)
    values (v_user, btrim(p_exam), btrim(p_subject), btrim(p_topic), btrim(p_title), p_is_public)
    returning id into v_id;
  else
    select id into v_id from public.decks
    where id = p_deck_id and owner_id = v_user for update;
    if not found then raise exception 'Conjunto não pertence à sua biblioteca.' using errcode = '42501'; end if;
    update public.decks set exam = btrim(p_exam), subject = btrim(p_subject),
      topic = btrim(p_topic), title = btrim(p_title), is_public = p_is_public
    where id = v_id;
  end if;
  if p_cards is not null then
    -- Substituição explícita; o app usa NULL para preservar cartões/progresso.
    delete from public.cards where deck_id = v_id;
    insert into public.cards(deck_id, front, back, position)
    select v_id, btrim(c.value ->> 'front'), btrim(c.value ->> 'back'), (c.ordinality - 1)::integer
    from jsonb_array_elements(p_cards) with ordinality c(value, ordinality);
  end if;
  return v_id;
end;
$$;

create function app_private.add_card(p_deck_id uuid, p_front text, p_back text)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
  v_id uuid;
  v_count integer;
  v_position integer;
begin
  if v_user is null then raise exception 'Entre na sua conta.' using errcode = '28000'; end if;
  perform 1 from public.decks where id = p_deck_id and owner_id = v_user for update;
  if not found then raise exception 'Conjunto não pertence à sua biblioteca.' using errcode = '42501'; end if;
  if p_front is null or p_back is null
     or char_length(btrim(p_front)) not between 1 and 30000
     or char_length(btrim(p_back)) not between 1 and 30000 then
    raise exception 'Informe frente e verso de até 30000 caracteres.' using errcode = '22023';
  end if;
  select count(*)::integer, coalesce(max(position), -1) + 1 into v_count, v_position
  from public.cards where deck_id = p_deck_id;
  if v_count >= 5000 then raise exception 'Limite de 5000 cartões por conjunto.' using errcode = '22023'; end if;
  insert into public.cards(deck_id, front, back, position)
  values (p_deck_id, btrim(p_front), btrim(p_back), v_position) returning id into v_id;
  return v_id;
end;
$$;

create function app_private.copy_public_decks(
  p_owner uuid,
  p_exam text default null,
  p_subject text default null,
  p_topic text default null
)
returns integer
language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
  v_source public.decks%rowtype;
  v_new_id uuid;
  v_count integer := 0;
begin
  if v_user is null then raise exception 'Entre na sua conta.' using errcode = '28000'; end if;
  if p_owner is null then raise exception 'Informe o autor.' using errcode = '22023'; end if;
  if p_owner = v_user then return 0; end if;
  -- Trava origem durante a cópia; alterações de cartões passam pela trava do conjunto.
  for v_source in
    select d.* from public.decks d
    where d.owner_id = p_owner and d.is_public
      and (p_exam is null or d.exam = p_exam)
      and (p_subject is null or d.subject = p_subject)
      and (p_topic is null or d.topic = p_topic)
    order by d.id for share
  loop
    v_new_id := null;
    insert into public.decks(owner_id, exam, subject, topic, title, is_public, source_deck_id)
    values (v_user, v_source.exam, v_source.subject, v_source.topic, v_source.title, false, v_source.id)
    on conflict (owner_id, source_deck_id) do nothing returning id into v_new_id;
    if v_new_id is not null then
      insert into public.cards(deck_id, front, back, position)
      select v_new_id, c.front, c.back, c.position
      from public.cards c where c.deck_id = v_source.id order by c.position;
      v_count := v_count + 1;
    end if;
  end loop;
  return v_count;
end;
$$;

create function app_private.begin_deck_study(p_deck_id uuid)
returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
  v_now timestamptz := now();
  v_review public.deck_reviews%rowtype;
begin
  if v_user is null then raise exception 'Entre na sua conta.' using errcode = '28000'; end if;
  perform 1 from public.decks where id = p_deck_id and owner_id = v_user for key share;
  if not found then raise exception 'Adicione o conjunto à sua biblioteca primeiro.' using errcode = '42501'; end if;
  insert into public.deck_reviews(user_id, deck_id, started_at, next_review_at)
  values (v_user, p_deck_id, v_now, v_now + interval '168 hours')
  on conflict (user_id, deck_id) do nothing;
  select * into v_review from public.deck_reviews
  where user_id = v_user and deck_id = p_deck_id for update;
  if v_review.next_review_at <= v_now then
    update public.deck_reviews
    set next_review_at = v_now + interval '360 hours', review_count = review_count + 1
    where user_id = v_user and deck_id = p_deck_id returning * into v_review;
  end if;
  return jsonb_build_object(
    'user_id', v_review.user_id, 'deck_id', v_review.deck_id,
    'started_at', v_review.started_at, 'next_review_at', v_review.next_review_at,
    'review_count', v_review.review_count
  );
end;
$$;

create function app_private.grade_card(p_card_id uuid, p_grade text, p_event_id uuid)
returns integer
language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
  v_delta integer;
  v_score integer;
  v_inserted uuid;
  v_previous public.grade_events%rowtype;
begin
  if v_user is null then raise exception 'Entre na sua conta.' using errcode = '28000'; end if;
  if p_event_id is null or p_grade is null or p_grade not in ('correct', 'wrong', 'doubt') then
    raise exception 'Resposta/evento inválido.' using errcode = '22023';
  end if;
  perform 1 from public.cards c join public.decks d on d.id = c.deck_id
  where c.id = p_card_id and d.owner_id = v_user for key share of c;
  if not found then raise exception 'Cartão não pertence à sua biblioteca.' using errcode = '42501'; end if;
  -- A chave única serializa reenvios concorrentes do mesmo evento.
  insert into public.grade_events(user_id, event_id, card_id, grade)
  values (v_user, p_event_id, p_card_id, p_grade)
  on conflict (user_id, event_id) do nothing returning event_id into v_inserted;
  if v_inserted is null then
    select * into v_previous from public.grade_events
    where user_id = v_user and event_id = p_event_id;
    if v_previous.card_id <> p_card_id or v_previous.grade <> p_grade then
      raise exception 'Evento já utilizado para outra resposta.' using errcode = '22023';
    end if;
    return v_previous.result_score;
  end if;
  v_delta := case p_grade when 'correct' then -1 when 'wrong' then 2 else 1 end;
  insert into public.card_progress(user_id, card_id, score, last_answered_at)
  values (v_user, p_card_id, greatest(0, v_delta), now())
  on conflict (user_id, card_id) do update
    set score = least(2147483647::bigint, greatest(0::bigint, public.card_progress.score::bigint + v_delta))::integer,
        last_answered_at = excluded.last_answered_at
  returning score into v_score;
  update public.grade_events set result_score = v_score
  where user_id = v_user and event_id = p_event_id;
  return v_score;
end;
$$;

-- Superfície REST sem privilégios próprios. A implementação privilegiada fica
-- em schema não exposto pela Data API; cada rotina valida auth.uid() e posse.
create function public.save_deck(
  p_deck_id uuid, p_exam text, p_subject text, p_topic text,
  p_title text, p_is_public boolean, p_cards jsonb
)
returns uuid language sql security invoker set search_path = ''
as $$ select app_private.save_deck(p_deck_id, p_exam, p_subject, p_topic, p_title, p_is_public, p_cards); $$;

create function public.add_card(p_deck_id uuid, p_front text, p_back text)
returns uuid language sql security invoker set search_path = ''
as $$ select app_private.add_card(p_deck_id, p_front, p_back); $$;

create function public.copy_public_decks(
  p_owner uuid, p_exam text default null, p_subject text default null, p_topic text default null
)
returns integer language sql security invoker set search_path = ''
as $$ select app_private.copy_public_decks(p_owner, p_exam, p_subject, p_topic); $$;

create function public.begin_deck_study(p_deck_id uuid)
returns jsonb language sql security invoker set search_path = ''
as $$ select app_private.begin_deck_study(p_deck_id); $$;

create function public.grade_card(p_card_id uuid, p_grade text, p_event_id uuid)
returns integer language sql security invoker set search_path = ''
as $$ select app_private.grade_card(p_card_id, p_grade, p_event_id); $$;

revoke all on function app_private.save_deck(uuid, text, text, text, text, boolean, jsonb)
  from public, anon, authenticated;
revoke all on function app_private.add_card(uuid, text, text) from public, anon, authenticated;
revoke all on function app_private.copy_public_decks(uuid, text, text, text) from public, anon, authenticated;
revoke all on function app_private.begin_deck_study(uuid) from public, anon, authenticated;
revoke all on function app_private.grade_card(uuid, text, uuid) from public, anon, authenticated;
grant usage on schema app_private to authenticated;
grant execute on function app_private.save_deck(uuid, text, text, text, text, boolean, jsonb) to authenticated;
grant execute on function app_private.add_card(uuid, text, text) to authenticated;
grant execute on function app_private.copy_public_decks(uuid, text, text, text) to authenticated;
grant execute on function app_private.begin_deck_study(uuid) to authenticated;
grant execute on function app_private.grade_card(uuid, text, uuid) to authenticated;

revoke all on function public.save_deck(uuid, text, text, text, text, boolean, jsonb)
  from public, anon, authenticated;
revoke all on function public.add_card(uuid, text, text) from public, anon, authenticated;
revoke all on function public.copy_public_decks(uuid, text, text, text) from public, anon, authenticated;
revoke all on function public.begin_deck_study(uuid) from public, anon, authenticated;
revoke all on function public.grade_card(uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.save_deck(uuid, text, text, text, text, boolean, jsonb) to authenticated;
grant execute on function public.add_card(uuid, text, text) to authenticated;
grant execute on function public.copy_public_decks(uuid, text, text, text) to authenticated;
grant execute on function public.begin_deck_study(uuid) to authenticated;
grant execute on function public.grade_card(uuid, text, uuid) to authenticated;

notify pgrst, 'reload schema';
commit;
