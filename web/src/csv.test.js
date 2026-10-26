import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseCards} from './csv.js';
test('importa aspas, separadores e respostas multilinha',()=>{
 assert.deepEqual(parseCards('\uFEFFpergunta;resposta\n"O que, exatamente?";"Linha 1\nLinha 2"'),[{front:'O que, exatamente?',back:'Linha 1\nLinha 2'}]);
 assert.deepEqual(parseCards('front,back\n"Diga ""oi""",resposta'),[{front:'Diga "oi"',back:'resposta'}]);
});
test('rejeita cartões inválidos e excesso de cartões',()=>{
 for(const source of ['', 'pergunta,resposta', 'a,b,c', 'a,', Array(5001).fill('a,b').join('\n')])assert.throws(()=>parseCards(source));
});
