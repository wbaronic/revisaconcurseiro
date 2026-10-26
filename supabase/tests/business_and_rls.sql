-- Executar como postgres após a migração, em um projeto de desenvolvimento.
-- Tudo é revertido. Não requer pgTAP.
begin;

create function pg_temp.assert_true(p_ok boolean, p_message text)
returns void language plpgsql as $$
begin
  if p_ok is distinct from true then
    raise exception 'FALHOU: %', p_message;
  end if;
end;
$$;

create function pg_temp.expect_error(p_sql text, p_state text)
returns void language plpgsql as $$
declare v_failed boolean := false;
begin
  begin
    execute p_sql;
  exception when others then
    if sqlstate <> p_state then
      raise exception 'SQLSTATE esperado %, recebido %: %', p_state, sqlstate, sqlerrm;
    end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'A instrução deveria falhar com %: %', p_state, p_sql; end if;
end;
$$;

insert into auth.users(id, email, raw_user_meta_data)
values
 ('a0000000-0000-4000-8000-000000000001', 'revisa-fixture-a@example.invalid',
  '{"username":"revisa_fixture_a","display_name":"Autor dos testes"}'),
 ('b0000000-0000-4000-8000-000000000002', 'revisa-fixture-b@example.invalid',
  '{"username":"revisa_fixture_b","display_name":"Aluna dos testes"}'),
 ('c0000000-0000-4000-8000-000000000003', 'revisa-fixture-c@example.invalid',
  '{"username":"revisa_fixture_a","display_name":"Duplicado"}');

select pg_temp.assert_true(
  (select username like 'aluno_%' from public.profiles
   where id = 'c0000000-0000-4000-8000-000000000003'),
  'Nome ocupado usa alternativa e não impede o cadastro'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', 'a0000000-0000-4000-8000-000000000001', true);
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-4000-8000-000000000001","role":"authenticated"}', true);

select set_config('revisa_test.public_deck',
  public.save_deck(null, 'Transpetro', 'Informática', 'Redes', 'Redes públicas', true,
    '[{"front":"O que é DNS?","back":"Sistema de nomes de domínio."},
      {"front":"O que é TCP?","back":"Protocolo de transporte."}]')::text, true);
select set_config('revisa_test.private_deck',
  public.save_deck(null, 'Transpetro', 'Português', 'Verbos', 'Privado', false,
    '[{"front":"Pergunta privada","back":"Resposta privada"}]')::text, true);
select set_config('revisa_test.source_card',
  (select id::text from public.cards where deck_id = current_setting('revisa_test.public_deck')::uuid and position = 0), true);
select pg_temp.assert_true(
  (select count(*) = 2 from public.decks where owner_id = auth.uid()),
  'Salvar conjuntos públicos e particulares'
);
select pg_temp.assert_true(
  (select count(*) = 0 from public.profiles where id = 'c0000000-0000-4000-8000-000000000003'),
  'Perfil sem conteúdo público não aparece para outro usuário'
);
select public.grade_card(current_setting('revisa_test.source_card')::uuid, 'wrong', gen_random_uuid());

-- Outra usuária: vê catálogo, mas não os dados particulares nem o progresso do autor.
select set_config('request.jwt.claim.sub', 'b0000000-0000-4000-8000-000000000002', true);
select set_config('request.jwt.claims', '{"sub":"b0000000-0000-4000-8000-000000000002","role":"authenticated"}', true);
select pg_temp.assert_true(
  (select count(*) = 1 from public.decks where owner_id = 'a0000000-0000-4000-8000-000000000001'),
  'RLS oculta conjuntos particulares'
);
select pg_temp.assert_true(
  (select count(*) = 2 from public.cards where deck_id = current_setting('revisa_test.public_deck')::uuid),
  'Catálogo permite ler cartões públicos'
);
select pg_temp.assert_true(
  (select count(*) = 0 from public.cards where deck_id = current_setting('revisa_test.private_deck')::uuid),
  'RLS oculta cartões particulares'
);
select pg_temp.assert_true(
  (select count(*) = 0 from public.card_progress),
  'Progresso público não é exposto'
);
select pg_temp.assert_true(
  (select username = 'revisa_fixture_a' from public.profiles where id = 'a0000000-0000-4000-8000-000000000001'),
  'Autor público é pesquisável'
);
select pg_temp.expect_error(
  $q$select public.grade_card(current_setting('revisa_test.source_card')::uuid, 'wrong', gen_random_uuid())$q$, '42501');
select pg_temp.expect_error(
  $q$select public.begin_deck_study(current_setting('revisa_test.public_deck')::uuid)$q$, '42501');
select pg_temp.expect_error(
  $q$select public.add_card(current_setting('revisa_test.public_deck')::uuid, 'invasão', 'invasão')$q$, '42501');
select pg_temp.expect_error(
  $q$select public.save_deck(current_setting('revisa_test.public_deck')::uuid,
  'Transpetro', 'Informática', 'Redes', 'Título invadido', false, null)$q$, '42501');
update public.decks set title = 'Título invadido'
where id = current_setting('revisa_test.public_deck')::uuid;
select pg_temp.assert_true(
  (select title = 'Redes públicas' from public.decks where id = current_setting('revisa_test.public_deck')::uuid),
  'PATCH de metadados de outro usuário não altera linhas'
);
delete from public.decks where id = current_setting('revisa_test.public_deck')::uuid;
select pg_temp.assert_true(
  (select count(*) = 1 from public.decks where id = current_setting('revisa_test.public_deck')::uuid),
  'DELETE de outro usuário não remove linhas'
);
select pg_temp.expect_error(
  $q$update public.decks set owner_id = auth.uid() where id = current_setting('revisa_test.public_deck')::uuid$q$, '42501');
select pg_temp.expect_error(
  $q$insert into public.card_progress(user_id, card_id, score) values (auth.uid(), current_setting('revisa_test.source_card')::uuid, 999)$q$, '42501');
select pg_temp.expect_error(
  $q$select * from public.grade_events$q$, '42501');

select pg_temp.assert_true(
  public.copy_public_decks('a0000000-0000-4000-8000-000000000001', 'Transpetro', 'Informática', 'Redes') = 1,
  'Cópia de assunto selecionado'
);
select pg_temp.assert_true(
  public.copy_public_decks('a0000000-0000-4000-8000-000000000001') = 0,
  'Importação repetida não duplica conjunto e não copia particulares'
);
select set_config('revisa_test.copied_deck',
  (select id::text from public.decks where owner_id = auth.uid()
   and source_deck_id = current_setting('revisa_test.public_deck')::uuid), true);
select set_config('revisa_test.copied_card',
  (select id::text from public.cards where deck_id = current_setting('revisa_test.copied_deck')::uuid and position = 0), true);
select pg_temp.assert_true(
  (select not is_public from public.decks where id = current_setting('revisa_test.copied_deck')::uuid),
  'Cópia começa particular'
);
select pg_temp.assert_true(
  current_setting('revisa_test.copied_card') <> current_setting('revisa_test.source_card'),
  'Cópia possui cartões e progresso independentes'
);

select pg_temp.assert_true(
  public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'correct', '10000000-0000-4000-8000-000000000001') = 0,
  'Acerto nunca torna a pontuação negativa'
);
select pg_temp.assert_true(
  public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'wrong', '10000000-0000-4000-8000-000000000002') = 2,
  'Erro soma dois'
);
select pg_temp.assert_true(
  public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'wrong', '10000000-0000-4000-8000-000000000002') = 2,
  'Reenvio do mesmo evento não soma novamente'
);
select pg_temp.expect_error(
  $q$select public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'doubt', '10000000-0000-4000-8000-000000000002')$q$, '22023');
select pg_temp.assert_true(
  public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'doubt', gen_random_uuid()) = 3,
  'Dúvida soma um'
);
select pg_temp.assert_true(
  public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'correct', gen_random_uuid()) = 2,
  'Acerto subtrai um'
);
select pg_temp.assert_true(
  (select score = 2 from public.card_progress where card_id = current_setting('revisa_test.copied_card')::uuid),
  'Progresso persiste o resultado'
);

select set_config('revisa_test.schedule',
  public.begin_deck_study(current_setting('revisa_test.copied_deck')::uuid)::text, true);
select pg_temp.assert_true(
  (current_setting('revisa_test.schedule')::jsonb ->> 'next_review_at')::timestamptz = now() + interval '168 hours',
  'Primeiro início agenda sete dias'
);
select pg_temp.assert_true(
  public.begin_deck_study(current_setting('revisa_test.copied_deck')::uuid) = current_setting('revisa_test.schedule')::jsonb,
  'Repetição antecipada não adia vencimento'
);

-- Apenas o teste, com privilégio de administração, faz a data vencer.
reset role;
update public.deck_reviews set next_review_at = now() - interval '48 hours'
where user_id = 'b0000000-0000-4000-8000-000000000002';
set local role authenticated;
select set_config('revisa_test.schedule',
  public.begin_deck_study(current_setting('revisa_test.copied_deck')::uuid)::text, true);
select pg_temp.assert_true(
  (current_setting('revisa_test.schedule')::jsonb ->> 'next_review_at')::timestamptz = now() + interval '360 hours'
  and (current_setting('revisa_test.schedule')::jsonb ->> 'review_count')::integer = 1,
  'Início vencido agenda quinze dias e conta uma revisão'
);
select pg_temp.assert_true(
  public.begin_deck_study(current_setting('revisa_test.copied_deck')::uuid) = current_setting('revisa_test.schedule')::jsonb,
  'Retentativa não conta nem adia outra revisão'
);
select pg_temp.expect_error(
  $q$update public.deck_reviews set next_review_at = now() + interval '100 years'$q$, '42501');

select public.save_deck(current_setting('revisa_test.copied_deck')::uuid,
  'Transpetro', 'Informática', 'Redes', 'Meu título', false, null);
select pg_temp.assert_true(
  (select count(*) = 2 from public.cards where deck_id = current_setting('revisa_test.copied_deck')::uuid)
  and (select score = 2 from public.card_progress where card_id = current_setting('revisa_test.copied_card')::uuid),
  'Editar metadados com p_cards NULL preserva cartões e progresso'
);
select pg_temp.expect_error(
  $q$select public.save_deck(current_setting('revisa_test.copied_deck')::uuid,
  'Transpetro', 'Informática', 'Redes', 'Não salvar', false, '[{"front":"faltou verso"}]'::jsonb)$q$, '22023');
select pg_temp.assert_true(
  (select title = 'Meu título' from public.decks where id = current_setting('revisa_test.copied_deck')::uuid),
  'Importação inválida não altera parcialmente metadados'
);
select public.add_card(current_setting('revisa_test.copied_deck')::uuid, 'Cartão avulso', 'Resposta');
select pg_temp.assert_true(
  (select count(*) = 3 and max(position) = 2 from public.cards where deck_id = current_setting('revisa_test.copied_deck')::uuid),
  'Cartão avulso é adicionado ao final'
);
select pg_temp.expect_error(
  $q$select public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'other', gen_random_uuid())$q$, '22023');
select pg_temp.assert_true(
  public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'wrong', gen_random_uuid()) = 4,
  'Erro durante revisão também soma dois'
);
select public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'correct', gen_random_uuid());
select public.grade_card(current_setting('revisa_test.copied_card')::uuid, 'correct', gen_random_uuid());

-- Autor remove a origem; a biblioteca da aluna mantém a cópia.
select set_config('request.jwt.claim.sub', 'a0000000-0000-4000-8000-000000000001', true);
select set_config('request.jwt.claims', '{"sub":"a0000000-0000-4000-8000-000000000001","role":"authenticated"}', true);
delete from public.decks where id = current_setting('revisa_test.public_deck')::uuid;
select set_config('request.jwt.claim.sub', 'b0000000-0000-4000-8000-000000000002', true);
select set_config('request.jwt.claims', '{"sub":"b0000000-0000-4000-8000-000000000002","role":"authenticated"}', true);
select pg_temp.assert_true(
  (select count(*) = 3 from public.cards where deck_id = current_setting('revisa_test.copied_deck')::uuid)
  and (select score = 2 from public.card_progress where card_id = current_setting('revisa_test.copied_card')::uuid),
  'Excluir origem não remove a cópia nem seu progresso'
);
select pg_temp.assert_true(
  not has_function_privilege('anon', 'public.grade_card(uuid,text,uuid)', 'execute')
  and not has_function_privilege('anon', 'public.copy_public_decks(uuid,text,text,text)', 'execute')
  and not has_schema_privilege('anon', 'app_private', 'usage')
  and not has_table_privilege('anon', 'public.decks', 'select'),
  'Usuários sem sessão não acessam conteúdo ou RPCs'
);
select pg_temp.assert_true(
  not (select prosecdef from pg_proc where oid = 'public.grade_card(uuid,text,uuid)'::regprocedure)
  and (select prosecdef from pg_proc where oid = 'app_private.grade_card(uuid,text,uuid)'::regprocedure),
  'RPC exposta usa privilégios do chamador; implementação privilegiada fica privada'
);
delete from public.decks where id = current_setting('revisa_test.copied_deck')::uuid;
select pg_temp.assert_true(
  (select count(*) = 0 from public.card_progress)
  and (select count(*) = 0 from public.deck_reviews),
  'Excluir conjunto próprio limpa progresso e agenda'
);

reset role;
rollback;
select 'OK: regras de negócio, isolamento entre usuários e privilégios verificados.' as result;
