begin;

create table public.simulations (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  title text not null check (char_length(btrim(title)) between 1 and 120),
  config jsonb not null check (jsonb_typeof(config) = 'array' and jsonb_array_length(config) between 1 and 100),
  created_at timestamptz not null default now()
);
create index simulations_owner_idx on public.simulations(owner_id, created_at desc);

create table public.simulation_attempts (
  id uuid primary key default gen_random_uuid(),
  simulation_id uuid references public.simulations(id) on delete set null,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  title text not null,
  card_ids uuid[] not null check (cardinality(card_ids) between 1 and 5000),
  answered_count integer not null default 0,
  correct_count integer not null default 0,
  percentage integer check (percentage between 0 and 100),
  created_at timestamptz not null default now(),
  completed_at timestamptz
);
create index simulation_attempts_owner_idx on public.simulation_attempts(owner_id, created_at desc);

create table public.simulation_answers (
  attempt_id uuid not null references public.simulation_attempts(id) on delete cascade,
  card_id uuid not null,
  event_id uuid not null,
  grade text not null check (grade in ('correct', 'wrong', 'doubt')),
  primary key(attempt_id, card_id),
  unique(attempt_id, event_id)
);

alter table public.simulations enable row level security;
alter table public.simulation_attempts enable row level security;
alter table public.simulation_answers enable row level security;
create policy simulation_select on public.simulations for select to authenticated using (owner_id = (select auth.uid()));
create policy simulation_insert on public.simulations for insert to authenticated with check (owner_id = (select auth.uid()));
create policy simulation_update on public.simulations for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create policy simulation_delete on public.simulations for delete to authenticated using (owner_id = (select auth.uid()));
create policy simulation_attempt_select on public.simulation_attempts for select to authenticated using (owner_id = (select auth.uid()));

revoke all on public.simulations, public.simulation_attempts, public.simulation_answers from public, anon, authenticated;
grant select, insert, delete on public.simulations to authenticated;
grant update(title, config) on public.simulations to authenticated;
grant select on public.simulation_attempts to authenticated;
grant all on public.simulations, public.simulation_attempts, public.simulation_answers to service_role;

create function app_private.start_simulation(p_simulation_id uuid)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
  v_sim public.simulations%rowtype;
  v_item jsonb;
  v_deck uuid;
  v_count integer;
  v_seen uuid[] := array[]::uuid[];
  v_cards uuid[] := array[]::uuid[];
  v_slice uuid[];
  v_attempt uuid;
begin
  if v_user is null then raise exception 'Entre na sua conta.' using errcode = '28000'; end if;
  select * into v_sim from public.simulations where id = p_simulation_id and owner_id = v_user;
  if not found then raise exception 'Simulado não encontrado.' using errcode = '42501'; end if;
  for v_item in select value from jsonb_array_elements(v_sim.config) as value loop
    if jsonb_typeof(v_item) <> 'object'
       or (v_item->>'deck_id') is null
       or (v_item->>'count') !~ '^[0-9]{1,4}$' then
      raise exception 'Configuração de simulado inválida.' using errcode = '22023';
    end if;
    v_deck := (v_item->>'deck_id')::uuid;
    v_count := (v_item->>'count')::integer;
    if v_count < 1 or v_deck = any(v_seen) then
      raise exception 'Conjuntos repetidos ou quantidade inválida.' using errcode = '22023';
    end if;
    perform 1 from public.decks where id = v_deck and owner_id = v_user;
    if not found then raise exception 'Um conjunto saiu da sua biblioteca.' using errcode = '42501'; end if;
    select array_agg(id) into v_slice
    from (select id from public.cards where deck_id = v_deck order by random() limit v_count) sample;
    if coalesce(cardinality(v_slice), 0) <> v_count then
      raise exception 'Um conjunto não possui a quantidade configurada de cartões.' using errcode = '22023';
    end if;
    v_seen := array_append(v_seen, v_deck);
    v_cards := v_cards || v_slice;
    if cardinality(v_cards) > 5000 then raise exception 'O limite por simulado é de 5000 cartões.' using errcode = '22023'; end if;
  end loop;
  select array_agg(id order by random()) into v_cards from unnest(v_cards) as id;
  insert into public.simulation_attempts(simulation_id, owner_id, title, card_ids)
  values(v_sim.id, v_user, v_sim.title, v_cards) returning id into v_attempt;
  return jsonb_build_object('attempt_id', v_attempt, 'card_ids', to_jsonb(v_cards), 'title', v_sim.title);
end;
$$;

create function app_private.grade_simulation_card(
  p_attempt_id uuid, p_card_id uuid, p_grade text, p_event_id uuid
)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
  v_attempt public.simulation_attempts%rowtype;
  v_answer public.simulation_answers%rowtype;
  v_score integer;
  v_answered integer;
  v_correct integer;
  v_percentage integer;
begin
  if v_user is null then raise exception 'Entre na sua conta.' using errcode = '28000'; end if;
  if p_grade not in ('correct', 'wrong', 'doubt') or p_event_id is null then
    raise exception 'Avaliação inválida.' using errcode = '22023';
  end if;
  select * into v_attempt from public.simulation_attempts
    where id = p_attempt_id and owner_id = v_user for update;
  if not found then raise exception 'Tentativa não encontrada.' using errcode = '42501'; end if;
  select * into v_answer from public.simulation_answers
    where attempt_id = p_attempt_id and card_id = p_card_id;
  if found then
    if v_answer.event_id <> p_event_id or v_answer.grade <> p_grade then
      raise exception 'Cartão já avaliado nesta tentativa.' using errcode = '22023';
    end if;
    select score into v_score from public.card_progress where user_id = v_user and card_id = p_card_id;
    return jsonb_build_object('score', v_score, 'complete', v_attempt.completed_at is not null, 'percentage', v_attempt.percentage);
  end if;
  if v_attempt.completed_at is not null or not p_card_id = any(v_attempt.card_ids) then
    raise exception 'Cartão fora da tentativa.' using errcode = '22023';
  end if;
  v_score := app_private.grade_card(p_card_id, p_grade, p_event_id);
  insert into public.simulation_answers(attempt_id, card_id, event_id, grade)
    values(p_attempt_id, p_card_id, p_event_id, p_grade);
  v_answered := v_attempt.answered_count + 1;
  v_correct := v_attempt.correct_count + case when p_grade = 'correct' then 1 else 0 end;
  if v_answered = cardinality(v_attempt.card_ids) then
    v_percentage := round(v_correct * 100.0 / v_answered)::integer;
  end if;
  update public.simulation_attempts
    set answered_count = v_answered, correct_count = v_correct,
        percentage = v_percentage,
        completed_at = case when v_percentage is null then null else now() end
    where id = p_attempt_id;
  return jsonb_build_object('score', v_score, 'complete', v_percentage is not null, 'percentage', v_percentage);
end;
$$;

create function public.start_simulation(p_simulation_id uuid)
returns jsonb language sql security invoker set search_path = ''
as $$ select app_private.start_simulation(p_simulation_id); $$;
create function public.grade_simulation_card(
  p_attempt_id uuid, p_card_id uuid, p_grade text, p_event_id uuid
)
returns jsonb language sql security invoker set search_path = ''
as $$ select app_private.grade_simulation_card(p_attempt_id, p_card_id, p_grade, p_event_id); $$;
revoke all on function app_private.start_simulation(uuid), app_private.grade_simulation_card(uuid,uuid,text,uuid),
  public.start_simulation(uuid), public.grade_simulation_card(uuid,uuid,text,uuid) from public, anon, authenticated;
grant execute on function app_private.start_simulation(uuid), app_private.grade_simulation_card(uuid,uuid,text,uuid),
  public.start_simulation(uuid), public.grade_simulation_card(uuid,uuid,text,uuid) to authenticated;
notify pgrst, 'reload schema';
commit;
