import Papa from 'papaparse';
export function parseCards(text) {
  const parsed = Papa.parse(text.replace(/^\uFEFF/, ''), { skipEmptyLines: 'greedy', delimitersToGuess: [',', ';', '\t'] });
  if (parsed.errors.length) throw new Error('CSV inválido: ' + parsed.errors[0].message);
  const rows = parsed.data.map(row => row.map(value => value.trim()));
  if (['pergunta|resposta','frente|verso','question|answer','front|back'].includes(rows[0]?.join('|').toLowerCase())) rows.shift();
  if (!rows.length || rows.length > 5000) throw new Error('O arquivo deve conter de 1 a 5.000 cartões.');
  return rows.map((row, i) => {
    if (row.length !== 2 || row.some(v => !v || v.length > 30000)) throw new Error(`Registro ${i + 1}: use duas colunas preenchidas, com até 30.000 caracteres cada.`);
    return { front: row[0], back: row[1] };
  });
}
