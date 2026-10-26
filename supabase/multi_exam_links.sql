begin;
create table public.deck_links (
 deck_id uuid not null references public.decks(id) on delete cascade,
 exam text not null check (char_length(btrim(exam)) between 1 and 160),
 subject text not null check (char_length(btrim(subject)) between 1 and 160),
 topic text not null check (char_length(btrim(topic)) between 1 and 160),
 primary key(deck_id,exam,subject,topic)
);
alter table public.deck_links enable row level security;
create policy links_read on public.deck_links for select to authenticated using (
 exists(select 1 from public.decks d where d.id=deck_id and (d.owner_id=(select auth.uid()) or d.is_public)));
create policy links_insert on public.deck_links for insert to authenticated with check (
 exists(select 1 from public.decks d where d.id=deck_id and d.owner_id=(select auth.uid())));
revoke all on public.deck_links from public,anon,authenticated;
grant select,insert on public.deck_links to authenticated;
grant all on public.deck_links to service_role;
-- A classificação original permanece para compatibilidade com versões anteriores.
-- Os vínculos adicionais nunca criam novos cartões ou novos conjuntos.
create or replace function app_private.copy_public_decks(p_owner uuid,p_exam text default null,p_subject text default null,p_topic text default null)
returns integer language plpgsql security definer set search_path='' as $$
declare v_user uuid:=auth.uid(); s public.decks%rowtype; v_id uuid; n integer:=0;
begin
 if v_user is null then raise exception 'Entre na sua conta.' using errcode='28000'; end if;
 if p_owner is null then raise exception 'Informe o autor.'; end if;
 if p_owner=v_user then return 0; end if;
 for s in select d.* from public.decks d where d.owner_id=p_owner and d.is_public and exists (
 select 1 from (select d.exam,d.subject,d.topic union select l.exam,l.subject,l.topic from public.deck_links l where l.deck_id=d.id) paths
 where (p_exam is null or paths.exam=p_exam) and (p_subject is null or paths.subject=p_subject) and (p_topic is null or paths.topic=p_topic))
 order by d.id for share
 loop
  v_id:=null;
  insert into public.decks(owner_id,exam,subject,topic,title,is_public,source_deck_id)
  values(v_user,s.exam,s.subject,s.topic,s.title,false,s.id)
  on conflict(owner_id,source_deck_id) do nothing returning id into v_id;
  if v_id is not null then
   insert into public.cards(deck_id,front,back,position) select v_id,front,back,position from public.cards where deck_id=s.id;
   n:=n+1;
  else
   select id into v_id from public.decks where owner_id=v_user and source_deck_id=s.id;
  end if;
  insert into public.deck_links(deck_id,exam,subject,topic)
  select v_id,exam,subject,topic from public.deck_links where deck_id=s.id on conflict do nothing;
 end loop;
 return n;
end;
$$;
notify pgrst,'reload schema';
commit;
