# Banco de dados do Revisa

Este diretório contém a migração PostgreSQL para Supabase e uma suíte de verificações transacional. Nenhum projeto remoto é criado ou alterado automaticamente.

## Configuração

1. Crie um projeto Supabase.
2. No **SQL Editor**, execute todo o arquivo `migrations/202610200001_initial.sql` uma vez.
   Depois aplique `multi_exam_links.sql` e `simulations.sql`, nessa ordem.
3. Em **Authentication → Providers → Email**, habilite o login por email e senha. Mantenha confirmação de email habilitada para contas reais.
4. Configure a URL do projeto e a chave **publishable** (ou `anon` legada) no aplicativo, conforme o README principal. A sessão autenticada envia seu próprio JWT. **Nunca coloque a chave `service_role` no app.**
5. Para confirmar emails, configure as URLs de redirecionamento em **Authentication → URL Configuration** e um destino de confirmação válido. Sem sessão retornada pelo cadastro, o aluno precisa confirmar o email antes de entrar.
6. Em um projeto de desenvolvimento, execute `tests/business_and_rls.sql` no SQL Editor. O arquivo abre uma transação, insere dados de teste e os reverte ao final. Um teste que falhar interrompe a execução; execute `ROLLBACK;` se a ferramenta deixar a transação aberta.

A migração destina-se a um projeto novo: nomes de tabelas/funções existentes devem ser avaliados antes de aplicá-la. Os perfis de contas já presentes em `auth.users` recebem nomes gerados.

## Modelo e acesso

| Tabela | Finalidade | Acesso do aplicativo |
|---|---|---|
| `profiles` | Nome público e identificador do usuário | Lê o próprio perfil e autores com conjunto público; altera o próprio nome |
| `decks` | Concurso → matéria → assunto → conjunto | Lê próprios/públicos; altera metadados e exclui próprios |
| `cards` | Frente, verso e ordem de cartões | Lê próprios/públicos; grava pelas RPCs |
| `deck_reviews` | Agenda individual por conjunto | Lê a própria agenda; grava pela RPC |
| `card_progress` | Pontuação individual por cartão | Lê o próprio progresso; grava pela RPC |
| `grade_events` | Identificadores para evitar respostas duplicadas | Acesso somente pela RPC |
| `simulations` | Modelos com a quantidade de cartões por conjunto | Cada aluno gerencia os próprios modelos |
| `simulation_attempts` | Tentativas e notas em porcentagem | Cada aluno lê as próprias notas |
| `simulation_answers` | Respostas de uma tentativa | Acesso somente pela RPC |

O simulado sorteia os cartões a cada tentativa. A nota é a porcentagem de respostas **Acertei**; **Errei** e **Dúvida** também atualizam a pontuação individual por cartão. Iniciar um simulado não altera a agenda de revisão dos conjuntos.

Todos os nomes ficam em UTF-8. O perfil não contém email: esse dado permanece no Supabase Auth. Pessoas não autenticadas não acessam essas tabelas.

O cadastro aceita `data: {"username":"nome_usuario","display_name":"Nome"}`. `username` é normalizado para minúsculas e aceita de 3 a 30 letras ASCII, números ou `_`. Se ausente, inválido ou ocupado, o servidor gera `aluno_<identificador>` para não bloquear o cadastro. O app deve mostrar o valor final de `profiles.username`. Alterações posteriores de username exigem esse formato e unicidade.

## Contratos das RPCs

As chamadas usam `POST /rest/v1/rpc/<nome>`, header `apikey` com a chave pública e `Authorization: Bearer <access_token>`. Números e UUIDs são retornados como escalares JSON; a agenda é objeto JSON.

### `save_deck`

```json
{
  "p_deck_id": null,
  "p_exam": "Transpetro",
  "p_subject": "Informática",
  "p_topic": "Redes",
  "p_title": "Conceitos de redes",
  "p_is_public": false,
  "p_cards": [{"front":"O que é DNS?","back":"Sistema de nomes de domínio."}]
}
```

Retorna o UUID do conjunto. `p_deck_id: null` cria um conjunto. Um UUID existente só pode ser alterado por seu proprietário. `p_cards: null` preserva os cartões e seu progresso; uma lista substitui todos os cartões e apaga o progresso dos cartões anteriores. Uma lista vazia permite preparar um conjunto para adicionar cartões depois. Frente e verso têm no máximo 30.000 caracteres; cada conjunto pode conter até 5.000 cartões. Validação e gravação acontecem na mesma transação.

### `add_card`

Recebe `p_deck_id`, `p_front`, `p_back` e retorna o UUID do cartão novo, adicionado no final. Exige conjunto próprio e respeita o limite de 5.000 cartões.

### `copy_public_decks`

Recebe `p_owner` (UUID do autor) e os filtros opcionais `p_exam`, `p_subject`, `p_topic`. `null` significa todos os itens desse nível. Retorna a quantidade de conjuntos novos copiados.

Exemplos: só autor = toda sua biblioteca pública; autor + concurso = concurso; + matéria = matéria; + assunto = assunto. A consulta do catálogo deve respeitar a mesma hierarquia ao preencher filtros.

Cada conjunto importado vira uma **cópia particular independente**, com cartões novos e pontuação/agenda zeradas. A cópia continua disponível quando o autor modifica, torna particular ou remove a origem. Repetir a importação não duplica o conjunto já copiado. Excluir uma cópia permite importá-la novamente. O proprietário pode publicar suas próprias cópias depois.

### `begin_deck_study`

Recebe `{"p_deck_id":"<uuid>"}`. Retorna:

```json
{
  "user_id": "<uuid>",
  "deck_id": "<uuid>",
  "started_at": "2026-10-20T12:00:00+00:00",
  "next_review_at": "2026-10-27T12:00:00+00:00",
  "review_count": 0
}
```

No primeiro início, agenda a revisão para 7 dias depois. Quando uma sessão começa com a revisão vencida, agenda a próxima para 15 dias após esse início e incrementa `review_count`. Inícios antecipados preservam a data existente. `started_at` representa o primeiro início; a contagem inclui apenas inícios de revisões já vencidas. A regra é baseada no início da sessão, conforme solicitado, mesmo que o aluno não conclua todos os cartões. Atrasos não geram várias revisões simultâneas.

### `grade_card`

Recebe `{"p_card_id":"<uuid>","p_grade":"wrong","p_event_id":"<uuid novo>"}` e retorna a pontuação resultante, por exemplo `2`.

| Valor | Resultado |
|---|---|
| `correct` | −1, com piso em zero |
| `wrong` | +2 |
| `doubt` | +1 |

A regra é a mesma para estudo de conjunto e revisão por cartão. O app cria **um UUID por resposta** e mantém o mesmo UUID ao reenviar aquela resposta depois de falha de rede. Eventos distintos são respostas distintas. Reenvio do mesmo evento retorna seu resultado original sem modificar a pontuação; reutilizá-lo para outro cartão ou outra resposta falha. Não há gravação direta de pontuação pelo app. O teto técnico é 2.147.483.647.

## Consultas de leitura

O filtro explícito de proprietário é necessário na biblioteca porque a política também permite ler conjuntos públicos de outras pessoas.

```text
GET /rest/v1/decks?owner_id=eq.<meu-uuid>&order=exam,subject,topic,title
GET /rest/v1/profiles?username=ilike.*<busca>*
GET /rest/v1/decks?owner_id=eq.<autor-uuid>&is_public=eq.true&order=exam,subject,topic,title
GET /rest/v1/cards?deck_id=eq.<uuid>&order=position
GET /rest/v1/deck_reviews?user_id=eq.<meu-uuid>&order=next_review_at
GET /rest/v1/card_progress?user_id=eq.<meu-uuid>&score=gt.0&select=*,cards(id,deck_id,front,back,position,decks(id,exam,subject,topic,title))&order=score.desc,last_answered_at.asc,card_id.asc
```

Codifique todos os parâmetros na URL. Em consultas com pesquisa por nome, escape sintaxe de filtros PostgREST. Use paginação (`Range` ou `limit`/`offset`) para bibliotecas grandes: a API pode limitar a quantidade de linhas por resposta. A fila de revisão combina **todos os cartões próprios com pontuação maior que zero**, em ordem decrescente; empate prioriza a resposta mais antiga e depois UUID. A dúvida também pode colocar um cartão nessa fila. Acertar uma vez um cartão com score 2 deixa score 1, portanto ele continua na fila.

## Segurança e verificação

- Todas as tabelas possuem RLS habilitada.
- Cada RPC identifica o usuário pelo JWT (`auth.uid()`), sem aceitar um dono/progresso arbitrário do cliente.
- As RPCs expostas em `public` usam `SECURITY INVOKER`. Suas implementações ficam em `app_private`, com `SECURITY DEFINER`, `search_path` vazio, nomes de tabela qualificados e checagem explícita de proprietário. Mantenha `app_private` fora da lista de schemas expostos pela Data API.
- Privilégios de escrita direta são limitados a nomes/metadados; usuário, origem, cartões, agenda e score não podem ser forjados.
- Travas de linha e restrições únicas protegem cópia, ordem dos cartões, pontuação e agenda contra requisições simultâneas.
- Chaves estrangeiras têm índices para que exclusões em cascata não percorram tabelas inteiras. O histórico de eventos permanece enquanto o cartão existir para garantir idempotência também em reenvios antigos.
- A suíte verifica isolamento entre usuários, leitura pública, cópias independentes, reenvios idempotentes, pontuação, 7/15 dias, preservação de data em início antecipado e bloqueio de escritas não autorizadas. Não substitui um teste de carga concorrente nem uma auditoria de produção.
- Importações são tratadas como conteúdo, não como comandos. O app deve exibir frente/verso como texto; não renderizar HTML arbitrário dos CSVs.

Referências oficiais: [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [funções de banco de dados](https://supabase.com/docs/guides/database/functions) e [perfis vinculados ao Auth](https://supabase.com/docs/guides/auth/managing-user-data).
