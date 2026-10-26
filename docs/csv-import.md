# Importação de flashcards

O aplicativo aceita arquivos de texto **UTF-8**, com ou sem BOM, contendo duas colunas: **pergunta** e **resposta**. O separador é identificado automaticamente entre vírgula, ponto e vírgula ou tabulação.

Não é necessário cabeçalho. Se houver, são reconhecidos os pares `Pergunta,Resposta`, `Frente,Verso`, `Question,Answer` e `Front,Back`, sem distinção entre maiúsculas e minúsculas. Somente a primeira linha de dados pode ser tratada como cabeçalho.

Exemplo:

```csv
Pergunta,Resposta
Qual é a função do DEV?,Permitir desenvolvimento e customizações.
"O que significa SAP?","Sistemas, Aplicações e Produtos."
"O que significa ""FI""?",Contabilidade financeira.
```

Textos com o separador devem ficar entre aspas duplas. Dentro de um campo entre aspas, escreva `""` para representar uma aspa. Campos entre aspas também podem conter quebras de linha. As quebras de linha internas são normalizadas para LF e espaços nas extremidades são removidos.

Linhas vazias são ignoradas. Perguntas ou respostas vazias, campos extras e aspas malformadas interrompem a importação com uma mensagem que identifica a linha inicial do registro inválido. O arquivo inteiro é validado antes de retornar os cartões; não há importação parcial. Cartões repetidos são preservados.

O limite é de **5.000 cartões por arquivo**. O cabeçalho e as linhas vazias não entram nessa contagem. Se duas interpretações do separador forem igualmente válidas, a vírgula tem prioridade; para evitar ambiguidades, use aspas em campos que contenham outros separadores.

Os arquivos anexados pelo usuário usam o formato aceito: duas colunas separadas por vírgula, sem cabeçalho, com aspas onde há vírgulas no texto. A conferência independente identificou **80 cartões em `flashcards.csv`** e **50 cartões em `flashcards (1).csv`**, sem campos vazios ou pares duplicados. Concurso, matéria, assunto e visibilidade são escolhidos no aplicativo durante a importação; não precisam constar no arquivo.

O módulo `shared` contém o importador e as regras de pontuação/agendamento, em Kotlin comum, reutilizáveis no Android e no futuro aplicativo iOS.
