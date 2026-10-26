# Revisa Web

Versão web conectada ao mesmo Supabase do Android. Use a mesma conta.

## Executar

Com Node.js instalado, abra um terminal nesta pasta e execute:

```sh
npm ci
npm run dev
```

Abra o endereço informado pelo Vite (normalmente http://127.0.0.1:5173).
Para gerar a versão de produção: `npm run build`. Publique a pasta `dist` em hospedagem estática com HTTPS.

## Funcionalidades

- Login e cadastro com nome de usuário, e-mail e senha.
- Importação CSV UTF-8 com duas colunas (pergunta/resposta), cabeçalho opcional, vírgula, ponto e vírgula ou tabulação; aspas e respostas multilinha.
- Prévia, limite de 5.000 cartões e 10 MB por arquivo; salvamento atômico pela função existente `save_deck`.
- Biblioteca compartilhada com Android, prévia dos cartões e visibilidade pública/particular.
- Busca por nome de usuário, filtros concurso → matéria → assunto e cópia pela função `copy_public_decks`.

O cliente usa somente a chave pública. As permissões são aplicadas pelo RLS e pelas funções existentes do banco. A confirmação de e-mail segue a configuração do Supabase Auth. Esta versão não inclui as sessões de revisão.
