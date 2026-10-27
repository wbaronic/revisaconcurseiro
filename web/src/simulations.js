import './simulations.css';
// Both clients use the same Supabase tables and grading RPCs.
export function createSimulations({ db, result, esc, notice }) {
  let templates = [], decks = [], history = [], attempt = null, busy = false;
  const main = () => document.querySelector('#main');

  async function rows(table, select, configure) {
    const all = [];
    for (let offset = 0; ; offset += 500) {
      const page = await result(configure(db.from(table).select(select)).range(offset, offset + 499));
      all.push(...page);
      if (page.length < 500) return all;
    }
  }

  async function load() {
    const user = (await db.auth.getUser()).data.user;
    if (!user) throw new Error('Entre na sua conta para abrir os simulados.');
    [templates, decks, history] = await Promise.all([
      rows('simulations', 'id,title,config', q => q.eq('owner_id', user.id).order('created_at', { ascending: false })),
      rows('decks', 'id,title,exam,subject,topic,cards(count)', q => q.eq('owner_id', user.id).order('title')),
      rows('simulation_attempts', 'id,title,percentage,completed_at',
        q => q.eq('owner_id', user.id).not('completed_at', 'is', null).order('completed_at', { ascending: false }))
    ]);
    render();
  }

  function render() {
    main().innerHTML = `<span class="eyebrow">Modelos de estudo</span><h1>Seus simulados.</h1>
      <p>Defina quantos cartões usar de cada conjunto. Cada tentativa sorteia cartões e guarda a nota em porcentagem.</p>
      <button id="newSimulation">+ Criar simulado</button>
      <h2>Modelos salvos</h2><div class="grid">${templates.length ? templates.map(s => `
        <article class="card"><h2>${esc(s.title)}</h2>
        <p>${s.config.map(p => `${p.count} de ${esc(decks.find(d => d.id === p.deck_id)?.title || 'conjunto removido')}`).join(' · ')}</p>
        <div class="row"><button data-study="${s.id}">Estudar</button><button class="secondary" data-edit="${s.id}">Editar</button><button class="secondary" data-delete="${s.id}">Excluir</button></div></article>`).join('') : '<p class="empty">Nenhum simulado criado ainda.</p>'}</div>
      <h2>Notas anteriores</h2><div class="panel">${history.length ? history.slice(0,30).map(h =>
        `<p><strong>${esc(h.title)}</strong> · ${h.percentage}% <small>${esc(h.completed_at.slice(0,10))}</small></p>`).join('') :
        '<p>Conclua um simulado para ver sua nota aqui.</p>'}</div>`;
    main().querySelector('#newSimulation').onclick = () => edit(null);
    main().querySelectorAll('[data-edit]').forEach(b => b.onclick = () => edit(templates.find(s => s.id === b.dataset.edit)));
    main().querySelectorAll('[data-delete]').forEach(b => b.onclick = () => action(async () => {
      if (!confirm('Excluir este modelo? As notas anteriores continuarão no histórico.')) return;
      await result(db.from('simulations').delete().eq('id', b.dataset.delete));
      await load();
      notice('Modelo excluído.');
    }, b));
    main().querySelectorAll('[data-study]').forEach(b => b.onclick = () => action(() => start(b.dataset.study), b));
  }

  function edit(template) {
    const usable = decks.filter(d => (d.cards?.[0]?.count || 0) > 0);
    main().innerHTML = `<span class="eyebrow">Simulado</span><h1>${template ? 'Editar modelo' : 'Criar modelo'}</h1>
      <form id="simulationForm" class="panel">
        <label>Nome do simulado<input name="title" required maxlength="120" value="${esc(template?.title || '')}" placeholder="Ex.: Básicas"></label>
        <h2>Cartões de cada conjunto</h2>
        ${usable.length ? usable.map(d => `<label>${esc(d.title)} · ${esc(d.subject)} · ${d.cards[0].count} disponíveis
          <input type="number" min="0" max="${d.cards[0].count}" step="1" name="deck_${d.id}"
            value="${template?.config.find(p => p.deck_id === d.id)?.count || 0}"></label>`).join('') :
          '<p>Adicione conjuntos à sua biblioteca antes de criar um simulado.</p>'}
        <p id="total">Total: ${template?.config.reduce((n,p) => n + p.count,0) || 0} cartões</p>
        <div class="row"><button ${usable.length ? '' : 'disabled'}>Salvar modelo</button><button type="button" class="secondary" id="cancel">Cancelar</button></div>
      </form>`;
    const form = main().querySelector('#simulationForm');
    form.oninput = () => { main().querySelector('#total').textContent =
      'Total: ' + usable.reduce((n,d) => n + Number(form.elements['deck_' + d.id].value || 0), 0) + ' cartões'; };
    main().querySelector('#cancel').onclick = render;
    form.onsubmit = e => {
      e.preventDefault();
      action(async () => {
        const config = usable.map(d => ({deck_id:d.id,count:Number(form.elements['deck_' + d.id].value || 0)}))
          .filter(p => p.count > 0);
        if (!config.length || config.length > 100 || config.reduce((n,p) => n + p.count,0) > 5000)
          throw new Error('Escolha de 1 a 5.000 cartões em até 100 conjuntos.');
        if (config.some(p => !Number.isInteger(p.count) || p.count > (usable.find(d => d.id === p.deck_id)?.cards[0].count || 0)))
          throw new Error('Confira a quantidade disponível em cada conjunto.');
        const title = form.elements.title.value.trim();
        if (!title) throw new Error('Informe o nome do simulado.');
        if (template) await result(db.from('simulations').update({title,config}).eq('id', template.id));
        else await result(db.from('simulations').insert({owner_id:(await db.auth.getUser()).data.user.id,title,config}));
        await load();
        notice('Modelo salvo. Clique em Estudar quando quiser iniciar uma nova tentativa.');
      }, e.submitter);
    };
  }

  async function start(simulationId) {
    const started = await result(db.rpc('start_simulation', {p_simulation_id:simulationId}));
    const ids = started.card_ids;
    const cardMap = new Map();
    for (let i = 0; i < ids.length; i += 200) {
      const cards = await result(db.from('cards').select('id,front,back').in('id', ids.slice(i,i + 200)));
      cards.forEach(card => cardMap.set(card.id, card));
    }
    if (ids.some(id => !cardMap.has(id))) throw new Error('Um cartão deste simulado não está disponível.');
    attempt = {id:started.attempt_id,title:started.title,cards:ids.map(id => cardMap.get(id)),
      index:0,revealed:false,seen:false,correct:0,doubt:0,wrong:0,eventId:crypto.randomUUID()};
    renderStudy();
  }

  function renderStudy() {
    const a = attempt, card = a.cards[a.index];
    main().innerHTML = `<span class="eyebrow">Simulado · ${a.index + 1}/${a.cards.length}</span>
      <h1>${esc(a.title)}</h1><div class="progress"><span style="width:${a.index / a.cards.length * 100}%"></span></div>
      <button id="flip" class="study-card ${a.revealed ? 'flipped' : ''}" aria-label="${a.revealed ? 'Mostrar pergunta' : 'Revelar resposta'}">
        <span class="study-side study-front"><small>PERGUNTA</small><strong>${esc(card.front)}</strong></span>
        <span class="study-side study-back"><small>RESPOSTA</small><strong>${esc(card.back)}</strong></span>
      </button>
      <div class="grade-row">${[['wrong','Errei'],['doubt','Dúvida'],['correct','Acertei']]
        .map(([grade,label]) => `<button data-grade="${grade}" ${a.seen ? '' : 'disabled'}>${label}</button>`).join('')}</div>
      <p>Toque no cartão para alternar entre pergunta e resposta. Erros e dúvidas entram na revisão por pontuação.</p>`;
    main().querySelector('#flip').onclick = e => {
      a.revealed = !a.revealed;
      if (a.revealed) a.seen = true;
      e.currentTarget.classList.toggle('flipped', a.revealed);
      e.currentTarget.setAttribute('aria-label', a.revealed ? 'Mostrar pergunta' : 'Revelar resposta');
      main().querySelectorAll('[data-grade]').forEach(button => button.disabled = !a.seen);
    };
    main().querySelectorAll('[data-grade]').forEach(b => b.onclick = () => action(async () => {
      const grade = b.dataset.grade;
      const answer = await result(db.rpc('grade_simulation_card', {p_attempt_id:a.id,p_card_id:card.id,p_grade:grade,p_event_id:a.eventId}));
      a[grade === 'correct' ? 'correct' : grade] += 1;
      if (answer.complete) {
        attempt = null;
        main().innerHTML = `<span class="eyebrow">Tentativa concluída</span><h1>Nota: ${answer.percentage}%</h1>
          <p>${a.correct} acertos, ${a.doubt} dúvidas e ${a.wrong} erros em ${a.cards.length} cartões. Sua nota foi salva.</p>
          <button id="backSimulations">Ver simulados e notas</button>`;
        main().querySelector('#backSimulations').onclick = () => action(load);
      } else {
        a.index += 1;
        a.revealed = false;
        a.seen = false;
        a.eventId = crypto.randomUUID();
        renderStudy();
      }
    }, b));
  }

  async function action(callback, button) {
    if (busy) return;
    busy = true;
    if (button) button.disabled = true;
    try { await callback(); }
    catch (error) { notice(error.message || 'Não foi possível concluir.', true); }
    finally { busy = false; if (button?.isConnected) button.disabled = false; }
  }

  return {load};
}
