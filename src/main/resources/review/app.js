'use strict';

// review page for a redo run: step through each problem's generated line, see the
// candidates behind every decision, and judge them. judgments are saved straight to
// the case library by the server.

const TAGS = ['ended-early', 'ended-late', 'wrong-resistance', 'unnatural-move', 'missed-tesuji',
  'wrong-solver-move', 'isolation-broken'];
const COLS = 'ABCDEFGHJKLMNOPQRST';
const END = 'END';

const state = {
  run: null,
  name: null,
  data: null,
  view: 'gen', // 'gen' or the index of a reference line
  dec: 0,      // selected decision on the generated line
  step: 0,     // board shown: moves played so far
  full: false,
  stakes: false,
};

const $ = (id) => document.getElementById(id);
const keyOf = (at) => (at.length ? at.join(' ') : 'root');
const pt = (move) => move.slice(2);
const colorName = (c) => (c === 'B' ? 'Black' : 'White');

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (ch) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}
function fmt(v, digits = 2) {
  if (v === null || v === undefined) return '';
  return typeof v === 'number' && !Number.isInteger(v) ? v.toFixed(digits) : String(v);
}
function gtpXY(g) {
  return [COLS.indexOf(g[0]), 19 - parseInt(g.slice(1), 10)];
}

async function api(path, opts) {
  const res = await fetch(path, opts);
  const body = await res.json();
  if (!res.ok) throw new Error(body.error || res.statusText);
  return body;
}

let toastTimer;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 1400);
}

// ---------- data helpers ----------

const record = () => state.data.record;
const caseFile = () => state.data.cases;
const caseAt = (at) => (caseFile().decisions || []).find((c) => keyOf(c.at) === keyOf(at));

function passes(c, chose) {
  if (c.expect.avoid.includes(chose)) return false;
  return c.expect.choose.length === 0 || c.expect.choose.includes(chose);
}

function decision() {
  return record().decisions[state.dec];
}

// what the author's RIGHT paths do at this position, if it lies on one
function authorMoves(at) {
  const out = new Set();
  for (const ref of record().refs) {
    if (ref.length < at.length) continue;
    if (!at.every((m, i) => ref[i] === m)) continue;
    out.add(ref.length === at.length ? END : ref[at.length]);
  }
  return [...out];
}

function lineFor(view) {
  return view === 'gen' ? record().generated : record().refs[view];
}
function boardsFor(view) {
  return view === 'gen' ? state.data.generatedBoards : state.data.refBoards[view];
}

// ---------- loading ----------

async function init() {
  try {
    state.run = await api('/api/run');
  } catch (e) {
    $('empty').textContent = 'Could not load the run: ' + e.message;
    return;
  }
  renderTop();
  renderProblems();
  const wanted = decodeURIComponent(location.hash.slice(1));
  const first = state.run.problems.find((p) => p.problem === wanted) || state.run.problems[0];
  if (first) await openProblem(first.problem);
  else $('empty').textContent = 'This run has no problems.';
}

async function openProblem(name) {
  state.name = name;
  history.replaceState(null, '', '#' + encodeURIComponent(name));
  state.data = await api('/api/problem?name=' + encodeURIComponent(name));
  state.view = 'gen';
  state.dec = 0;
  state.step = record().decisions.length ? record().decisions[0].at.length : 0;
  $('empty').hidden = !!record().isolation;
  $('boardPane').hidden = !record().isolation;
  $('side').hidden = !record().isolation;
  if (!record().isolation) {
    $('empty').textContent = name + ' failed in this run: ' + (record().error || 'no isolation recorded');
  }
  renderProblems();
  if (record().isolation) render();
}

// ---------- rendering ----------

function renderTop() {
  const m = state.run.manifest;
  const s = m.summary;
  const model = (m.model || '').split(/[\\/]/).pop();
  const parts = ['run ' + m.id, 'commit ' + m.commit, model];
  if (s) parts.push(`first move ${s.firstMoveMatch}/${s.evaluated} · exact ${s.exact}/${s.evaluated}`);
  parts.push('reviewing as ' + state.run.by);
  $('runInfo').textContent = parts.join('  ·  ');
}

function renderProblems() {
  const nav = $('problems');
  nav.innerHTML = '';
  for (const p of state.run.problems) {
    const b = document.createElement('button');
    b.className = 'prob' + (p.problem === state.name ? ' sel' : '');
    const m = p.metrics;
    const badges = [];
    if (!p.error) {
      badges.push(`<span class="badge ${p.judged === p.decisions ? 'good' : ''}" title="decisions with a case">${p.judged}/${p.decisions}</span>`);
      if (p.failing) badges.push(`<span class="badge bad" title="decisions this run gets wrong">${p.failing} wrong</span>`);
      if (p.accepted) badges.push('<span class="badge good" title="line accepted">accepted</span>');
    }
    const meta = p.error ? 'error: ' + esc(p.error)
      : m ? `prefix ${m.prefix} · ${m.genLen} vs ${m.refLen} moves${m.exact ? ' · exact' : ''}` : '';
    b.innerHTML = `<div class="name">${esc(p.problem)} ${badges.join(' ')}</div><div class="meta">${meta}</div>`;
    b.onclick = () => openProblem(p.problem);
    nav.appendChild(b);
  }
}

function render() {
  renderTabs();
  renderBoard();
  renderSide();
}

function renderTabs() {
  const tabs = $('lineTabs');
  tabs.innerHTML = '';
  const add = (label, view) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.className = state.view === view ? 'sel' : '';
    b.onclick = () => setView(view);
    tabs.appendChild(b);
  };
  add(`Generated (${record().generated.length})`, 'gen');
  record().refs.forEach((ref, i) => add(`Author ${i + 1} (${ref.length})`, i));
}

function setView(view) {
  state.view = view;
  if (view === 'gen') {
    state.step = record().decisions.length ? decision().at.length : 0;
  } else {
    state.step = Math.min(state.step, boardsFor(view).length - 1);
  }
  render();
}

function renderBoard() {
  const boards = boardsFor(state.view);
  const line = lineFor(state.view);
  const step = Math.min(state.step, boards.length - 1);
  const rows = boards[step];
  const iso = record().isolation;
  const d = state.view === 'gen' && record().decisions.length ? decision() : null;
  const c = d ? caseAt(d.at) : null;

  // crop to the problem: every real stone on any line plus candidate points
  let x0 = 18, y0 = 18, x1 = 0, y1 = 0;
  const grow = (x, y) => { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); };
  for (const bs of [state.data.generatedBoards, ...state.data.refBoards]) {
    for (const b of bs) b.forEach((row, y) => [...row].forEach((ch, x) => { if (ch === 'X' || ch === 'O') grow(x, y); }));
  }
  if (d) for (const cand of d.candidates) if (pt(cand.move) !== 'pass') grow(...gtpXY(pt(cand.move)));
  if (state.full || x1 < x0) { x0 = 0; y0 = 0; x1 = 18; y1 = 18; } else {
    x0 = Math.max(0, x0 - 2); y0 = Math.max(0, y0 - 2); x1 = Math.min(18, x1 + 2); y1 = Math.min(18, y1 + 2);
  }

  const cell = 34, margin = 26;
  const w = (x1 - x0) * cell + 2 * margin, h = (y1 - y0) * cell + 2 * margin;
  const X = (x) => margin + (x - x0) * cell, Y = (y) => margin + (y - y0) * cell;
  const parts = [];
  parts.push(`<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="board">`);
  parts.push(`<rect width="${w}" height="${h}" rx="6" fill="var(--wood)"/>`);
  const ext = cell * 0.45;
  for (let x = x0; x <= x1; x++) {
    const top = Y(y0) - (y0 > 0 ? ext : 0), bottom = Y(y1) + (y1 < 18 ? ext : 0);
    parts.push(`<line x1="${X(x)}" y1="${top}" x2="${X(x)}" y2="${bottom}" stroke="var(--grid)" stroke-width="1"/>`);
    parts.push(`<text x="${X(x)}" y="${margin * 0.45}" font-size="11" text-anchor="middle" fill="var(--grid)">${COLS[x]}</text>`);
  }
  for (let y = y0; y <= y1; y++) {
    const left = X(x0) - (x0 > 0 ? ext : 0), right = X(x1) + (x1 < 18 ? ext : 0);
    parts.push(`<line x1="${left}" y1="${Y(y)}" x2="${right}" y2="${Y(y)}" stroke="var(--grid)" stroke-width="1"/>`);
    parts.push(`<text x="${margin * 0.4}" y="${Y(y) + 4}" font-size="11" text-anchor="middle" fill="var(--grid)">${19 - y}</text>`);
  }
  for (const sx of [3, 9, 15]) for (const sy of [3, 9, 15]) {
    if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) parts.push(`<circle cx="${X(sx)}" cy="${Y(sy)}" r="2.6" fill="var(--grid)"/>`);
  }

  const last = step > 0 ? line[step - 1] : null;
  const lastXY = last ? gtpXY(pt(last)) : null;
  const stakes = new Set(iso.stakes);
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const ch = rows[y][x];
      if (ch === '.') continue;
      const black = ch === 'X' || ch === 'x';
      const filled = ch === 'x' || ch === 'o';
      parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.47}" fill="${black ? '#151515' : '#f7f7f4'}"
        stroke="${black ? '#000' : '#444'}" stroke-width="1" opacity="${filled ? 0.32 : 1}"/>`);
      const mark = black ? '#f7f7f4' : '#151515';
      if (lastXY && lastXY[0] === x && lastXY[1] === y) {
        parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.18}" fill="none" stroke="${mark}" stroke-width="2"/>`);
      } else if (state.stakes && stakes.has(COLS[x] + (19 - y))) {
        const r = cell * 0.2;
        parts.push(`<polygon points="${X(x)},${Y(y) - r} ${X(x) - r},${Y(y) + r * 0.75} ${X(x) + r},${Y(y) + r * 0.75}" fill="none" stroke="${mark}" stroke-width="1.6"/>`);
      }
    }
  }

  // the decision's candidates, numbered as in the table; expected moves ringed
  if (d && step === d.at.length) {
    const expect = c ? c.expect : { choose: [], avoid: [] };
    const labeled = new Set();
    d.candidates.forEach((cand, i) => {
      if (pt(cand.move) === 'pass') return;
      const [x, y] = gtpXY(pt(cand.move));
      if (rows[y][x] !== '.') return;
      labeled.add(cand.move);
      const chosen = cand.move === d.chose;
      const color = expect.choose.includes(cand.move) ? 'var(--good)' : expect.avoid.includes(cand.move) ? 'var(--bad)'
        : chosen ? 'var(--accent)' : '#3a2a12';
      parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.36}" fill="var(--wood)" stroke="${color}" stroke-width="${chosen ? 3 : 1.5}"/>`);
      parts.push(`<text x="${X(x)}" y="${Y(y) + 5}" font-size="14" font-weight="700" text-anchor="middle" fill="${color}">${i + 1}</text>`);
    });
    for (const m of expect.choose) {
      if (m === END || labeled.has(m)) continue;
      const [x, y] = gtpXY(pt(m));
      parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.36}" fill="none" stroke="var(--good)" stroke-width="3"/>`);
    }
    for (const m of expect.avoid) {
      if (m === END || labeled.has(m)) continue;
      const [x, y] = gtpXY(pt(m));
      const r = cell * 0.25;
      parts.push(`<path d="M${X(x) - r} ${Y(y) - r}L${X(x) + r} ${Y(y) + r}M${X(x) + r} ${Y(y) - r}L${X(x) - r} ${Y(y) + r}" stroke="var(--bad)" stroke-width="3"/>`);
    }
  }
  parts.push('</svg>');
  const holder = $('board');
  holder.innerHTML = parts.join('');
  holder.querySelector('svg').onclick = (ev) => {
    if (!d || step !== d.at.length) return;
    const svg = ev.currentTarget;
    const p = svg.createSVGPoint();
    p.x = ev.clientX; p.y = ev.clientY;
    const loc = p.matrixTransform(svg.getScreenCTM().inverse());
    const x = Math.round((loc.x - margin) / cell) + x0, y = Math.round((loc.y - margin) / cell) + y0;
    if (x < x0 || x > x1 || y < y0 || y > y1 || rows[y][x] !== '.') return;
    toggleRight(d.toMove + ' ' + COLS[x] + (19 - y));
  };

  $('stepLabel').textContent = state.view === 'gen'
    ? (d ? `decision ${state.dec + 1} of ${record().decisions.length} · after ${d.at.length} moves` : 'no decisions')
    : `author line ${state.view + 1} · move ${step} of ${line.length}`;
  $('showStakes').checked = state.stakes;
  $('fullBoard').checked = state.full;
  const notes = [...(record().notes || [])];
  $('notes').innerHTML = notes.map((n) => `<div>${esc(n)}</div>`).join('');
}

function renderSide() {
  const rec = record();
  const moves = $('moves');
  moves.innerHTML = '';
  if (state.view !== 'gen') {
    const ref = rec.refs[state.view];
    let prefix = 0;
    while (prefix < ref.length && ref[prefix] === rec.generated[prefix]) prefix++;
    $('lineHead').innerHTML = `<b>Author line ${state.view + 1}</b> · ${ref.length} moves · shares ${prefix} with the generated line`;
    ref.forEach((m, i) => {
      const b = document.createElement('button');
      b.className = state.step === i + 1 ? 'sel' : '';
      b.innerHTML = `<span class="stone ${m[0]}"></span>${i + 1}. ${esc(pt(m))}`;
      b.onclick = () => { state.step = i + 1; render(); };
      const li = document.createElement('li');
      li.appendChild(b);
      moves.appendChild(li);
    });
    $('decision').innerHTML = '<p class="muted">Author lines are for comparison. Switch to the generated line to judge decisions.</p>';
    return;
  }

  const accepted = (caseFile().acceptedLines || []).some((l) => l.join(' ') === rec.generated.join(' '));
  const m = rec.metrics;
  $('lineHead').innerHTML = `<b>Generated line</b> · ${rec.generated.length} moves`
    + (m ? ` · author ${m.refLen}, shared prefix ${m.prefix}${m.exact ? ', exact' : ''}` : '')
    + (accepted ? ' · <span class="badge good">accepted</span>' : '');
  rec.decisions.forEach((d, i) => {
    const c = caseAt(d.at);
    const b = document.createElement('button');
    b.className = i === state.dec ? 'sel' : '';
    const dot = c ? (passes(c, d.chose) ? 'good' : 'bad') : '';
    const label = d.chose === END ? 'end' : pt(d.chose);
    b.innerHTML = `<span class="dot ${dot}"></span><span class="stone ${d.toMove}"></span>${d.at.length + 1}. ${esc(label)}`;
    b.title = `${d.role}: ${d.reason || ''}`;
    b.onclick = () => selectDecision(i);
    const li = document.createElement('li');
    li.appendChild(b);
    moves.appendChild(li);
  });
  renderDecision();
}

function renderDecision() {
  const el = $('decision');
  const rec = record();
  if (!rec.decisions.length) { el.innerHTML = '<p class="muted">No decisions recorded.</p>'; return; }
  const d = decision();
  const c = caseAt(d.at);
  const expect = c ? c.expect : { choose: [], avoid: [] };
  const author = authorMoves(d.at);
  const choseLabel = d.chose === END ? 'end the line here' : 'play ' + pt(d.chose);

  let status;
  if (!c) status = '<div class="status none">No case yet. <kbd>g</kbd> right, <kbd>b</kbd> wrong, or pick the right move below.</div>';
  else {
    const ok = passes(c, d.chose);
    const exp = [expect.choose.length ? 'right: ' + expect.choose.map(lbl).join(', ') : '',
      expect.avoid.length ? 'wrong: ' + expect.avoid.map(lbl).join(', ') : ''].filter(Boolean).join(' · ');
    status = `<div class="status ${ok ? 'pass' : 'fail'}">${ok ? 'This run gets it right' : 'This run gets it wrong'}
      · ${esc(exp)} <span class="muted">(${esc(c.source)}${c.by && c.by !== c.source ? ', ' + esc(c.by) : ''})</span></div>`;
  }

  const isRight = expect.choose.includes(d.chose), isWrong = expect.avoid.includes(d.chose);
  const endHereOn = d.chose !== END && isWrong && expect.choose.includes(END);
  const actions = [
    `<button class="good ${isRight ? 'on' : ''}" data-act="good">Right <kbd>g</kbd></button>`,
    `<button class="bad ${isWrong && !endHereOn ? 'on' : ''}" data-act="bad">${d.chose === END ? 'Should continue' : 'Wrong'} <kbd>b</kbd></button>`,
    d.role === 'opponent' && d.chose !== END ? `<button class="bad ${endHereOn ? 'on' : ''}" data-act="end">Should end here <kbd>e</kbd></button>` : '',
    `<button data-act="clear">Clear <kbd>x</kbd></button>`,
    c ? `<button data-act="delete">Delete case <kbd>d</kbd></button>` : '',
    `<button data-act="accept" title="mark every unjudged decision right and keep the line as acceptable">Accept line <kbd>A</kbd></button>`,
  ].join('');

  const opp = d.role === 'opponent';
  const head = opp
    ? '<th></th><th class="l">#</th><th class="l">move</th><th class="l">from</th><th>prior</th><th>visits</th><th>human</th><th>threat</th><th class="l">answer</th><th>ans prior</th><th>human ans</th><th>stake</th>'
    : '<th></th><th class="l">#</th><th class="l">move</th><th>prior</th><th>visits</th><th>score</th>';
  const rows = d.candidates.map((cand, i) => {
    const mark = expect.choose.includes(cand.move) ? '<td class="mark good">✓</td>'
      : expect.avoid.includes(cand.move) ? '<td class="mark bad">✗</td>' : '<td class="mark"></td>';
    const cls = cand.move === d.chose ? 'chosen' : '';
    const cells = opp
      ? `<td class="l">${esc(pt(cand.move))}${author.includes(cand.move) ? ' <span class="muted" title="the author plays this">★</span>' : ''}</td>
         <td class="l dim">${esc(cand.source)}</td><td>${fmt(cand.prior)}</td><td>${fmt(cand.visits)}</td>
         <td>${fmt(cand.humanPolicy)}</td><td>${cand.error ? 'err' : fmt(cand.threat)}</td><td class="l">${esc(cand.answer || '')}</td>
         <td>${fmt(cand.answerPrior)}</td><td>${fmt(cand.humanAnswer)}</td><td>${fmt(cand.stakeDist)}</td>`
      : `<td class="l">${esc(pt(cand.move))}${author.includes(cand.move) ? ' <span class="muted" title="the author plays this">★</span>' : ''}</td>
         <td>${fmt(cand.prior)}</td><td>${fmt(cand.visits)}</td><td>${fmt(cand.scoreLead, 1)}</td>`;
    return `<tr class="${cls}" data-move="${esc(cand.move)}">${mark}<td class="l dim">${i + 1}</td>${cells}</tr>`;
  });
  if (opp) {
    const mark = expect.choose.includes(END) ? '<td class="mark good">✓</td>'
      : expect.avoid.includes(END) ? '<td class="mark bad">✗</td>' : '<td class="mark"></td>';
    rows.push(`<tr class="${d.chose === END ? 'chosen' : ''}" data-move="${END}">${mark}<td class="l dim">0</td>
      <td class="l" colspan="11">end the line${author.includes(END) ? ' <span class="muted">★</span>' : ''}</td></tr>`);
  }

  const tags = [...new Set([...TAGS, ...((c && c.tags) || [])])].map((t) =>
    `<button class="tag ${c && c.tags.includes(t) ? 'on' : ''}" data-tag="${esc(t)}">${esc(t)}</button>`).join('');

  const authorText = author.length
    ? 'Author here: ' + author.map(lbl).join(', ')
    : 'Off the author\'s RIGHT paths';
  el.innerHTML = `
    <h3>${colorName(d.toMove)} to play · ${esc(d.role)}</h3>
    <div class="reason">Chose to ${esc(choseLabel)}: ${esc(d.reason || '')}<br>${esc(authorText)}</div>
    ${status}
    <div class="actions">${actions}</div>
    <table class="cands"><thead><tr>${head}</tr></thead><tbody>${rows.join('')}</tbody></table>
    <div class="muted" style="font-size:12px">Click a row (or press its number, or click the board) to toggle it as right. ★ marks the author's move.</div>
    <div class="section-label">Tags</div>
    <div class="tags">${tags}<input id="tagInput" placeholder="new tag…" aria-label="new tag"></div>
    <div class="section-label">Note</div>
    <textarea id="noteInput" placeholder="why, in a sentence">${esc((c && c.note) || '')}</textarea>`;

  el.querySelectorAll('[data-act]').forEach((b) => { b.onclick = () => act(b.dataset.act); });
  el.querySelectorAll('tr[data-move]').forEach((tr) => { tr.onclick = () => toggleRight(tr.dataset.move); });
  el.querySelectorAll('[data-tag]').forEach((b) => { b.onclick = () => toggleTag(b.dataset.tag); });
  const tagInput = $('tagInput');
  tagInput.onkeydown = (ev) => {
    if (ev.key === 'Enter' && tagInput.value.trim()) { toggleTag(tagInput.value.trim().toLowerCase().replace(/\s+/g, '-')); }
    ev.stopPropagation();
  };
  const note = $('noteInput');
  note.onchange = () => save({ note: note.value });
  note.onkeydown = (ev) => { ev.stopPropagation(); if (ev.key === 'Escape') note.blur(); };
}

function lbl(m) {
  return m === END ? 'end' : pt(m);
}

// ---------- actions ----------

function selectDecision(i) {
  state.dec = Math.max(0, Math.min(record().decisions.length - 1, i));
  state.step = decision().at.length;
  render();
}

function currentEdit() {
  const d = decision();
  const c = caseAt(d.at);
  return {
    d,
    expect: c ? { choose: [...c.expect.choose], avoid: [...c.expect.avoid] } : { choose: [], avoid: [] },
    tags: c ? [...c.tags] : [],
    note: c ? c.note || '' : '',
    exists: !!c,
  };
}

const without = (list, m) => list.filter((x) => x !== m);

async function act(kind) {
  if (state.view !== 'gen' || !record().decisions.length) return;
  if (kind === 'accept') return acceptLine();
  const e = currentEdit();
  const chose = e.d.chose;
  if (kind === 'delete') {
    if (!e.exists) return;
    state.data.cases = await api(`/api/case-delete?name=${encodeURIComponent(state.name)}&key=${encodeURIComponent(keyOf(e.d.at))}`, { method: 'POST' });
    return afterSave('case deleted');
  }
  if (kind === 'good') {
    const on = e.expect.choose.includes(chose);
    e.expect.choose = without(e.expect.choose, chose);
    e.expect.avoid = without(e.expect.avoid, chose);
    if (!on) e.expect.choose.push(chose);
  } else if (kind === 'bad') {
    const on = e.expect.avoid.includes(chose);
    e.expect.choose = without(e.expect.choose, chose);
    e.expect.avoid = without(e.expect.avoid, chose);
    if (!on) e.expect.avoid.push(chose);
  } else if (kind === 'end') {
    // ending replaces the opponent's response; a solver decision cannot end the line
    if (chose === END || e.d.role !== 'opponent') return;
    e.expect.choose = without(e.expect.choose, chose);
    if (!e.expect.avoid.includes(chose)) e.expect.avoid.push(chose);
    e.expect.avoid = without(e.expect.avoid, END);
    if (!e.expect.choose.includes(END)) e.expect.choose.push(END);
  } else if (kind === 'clear') {
    e.expect.choose = without(e.expect.choose, chose);
    e.expect.avoid = without(e.expect.avoid, chose);
  }
  await saveEdit(e);
}

async function toggleRight(move) {
  if (state.view !== 'gen' || !record().decisions.length) return;
  const e = currentEdit();
  if (e.expect.choose.includes(move)) e.expect.choose = without(e.expect.choose, move);
  else { e.expect.choose.push(move); e.expect.avoid = without(e.expect.avoid, move); }
  await saveEdit(e);
}

async function toggleTag(tag) {
  const e = currentEdit();
  e.tags = e.tags.includes(tag) ? without(e.tags, tag) : [...e.tags, tag];
  await saveEdit(e);
}

async function save(fields) {
  const e = currentEdit();
  Object.assign(e, fields);
  await saveEdit(e);
}

async function saveEdit(e) {
  const empty = !e.expect.choose.length && !e.expect.avoid.length && !e.tags.length && !e.note.trim();
  try {
    if (empty) {
      if (!e.exists) return render();
      state.data.cases = await api(`/api/case-delete?name=${encodeURIComponent(state.name)}&key=${encodeURIComponent(keyOf(e.d.at))}`, { method: 'POST' });
      return afterSave('case removed');
    }
    state.data.cases = await api('/api/case?name=' + encodeURIComponent(state.name), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ at: e.d.at, expect: e.expect, tags: e.tags, note: e.note }),
    });
    afterSave('saved');
  } catch (err) {
    toast('save failed: ' + err.message);
  }
}

async function acceptLine() {
  const rec = record();
  const accepted = (caseFile().acceptedLines || []).some((l) => l.join(' ') === rec.generated.join(' '));
  const path = accepted ? '/api/unaccept-line' : '/api/accept-line';
  state.data.cases = await api(path + '?name=' + encodeURIComponent(state.name), { method: 'POST' });
  afterSave(accepted ? 'line no longer accepted' : 'line accepted');
}

function afterSave(msg) {
  // refresh this problem's counts in the list
  const p = state.run.problems.find((x) => x.problem === state.name);
  if (p) {
    let judged = 0, failing = 0;
    for (const d of record().decisions) {
      const c = caseAt(d.at);
      if (!c) continue;
      judged++;
      if (!passes(c, d.chose)) failing++;
    }
    p.judged = judged;
    p.failing = failing;
    p.accepted = (caseFile().acceptedLines || []).some((l) => l.join(' ') === record().generated.join(' '));
  }
  renderProblems();
  render();
  toast(msg);
}

// ---------- keyboard ----------

document.addEventListener('keydown', (ev) => {
  if (ev.target.matches('input, textarea') || ev.metaKey || ev.ctrlKey || ev.altKey) return;
  if (!state.data || !record().isolation) return;
  const k = ev.key;
  const problems = state.run.problems;
  const pi = problems.findIndex((p) => p.problem === state.name);
  if (k === 'ArrowRight' || k === 'ArrowLeft') {
    const dir = k === 'ArrowRight' ? 1 : -1;
    if (state.view === 'gen') selectDecision(state.dec + dir);
    else { state.step = Math.max(0, Math.min(boardsFor(state.view).length - 1, state.step + dir)); render(); }
  } else if (k === 'ArrowDown' && pi < problems.length - 1) openProblem(problems[pi + 1].problem);
  else if (k === 'ArrowUp' && pi > 0) openProblem(problems[pi - 1].problem);
  else if (k === 'g') act('good');
  else if (k === 'b') act('bad');
  else if (k === 'e') act('end');
  else if (k === 'x') act('clear');
  else if (k === 'd') act('delete');
  else if (k === 'A') act('accept');
  else if (k === 'r') {
    const views = ['gen', ...record().refs.map((_, i) => i)];
    setView(views[(views.indexOf(state.view) + 1) % views.length]);
  } else if (k === 's') { state.stakes = !state.stakes; renderBoard(); }
  else if (k === 'f') { state.full = !state.full; renderBoard(); }
  else if (k === '?') $('help').showModal();
  else if (/^[0-9]$/.test(k) && state.view === 'gen') {
    const d = decision();
    if (k === '0') { if (d.role === 'opponent') toggleRight(END); }
    else if (d.candidates[+k - 1]) toggleRight(d.candidates[+k - 1].move);
  } else return;
  ev.preventDefault();
});

$('showStakes').onchange = (ev) => { state.stakes = ev.target.checked; renderBoard(); };
$('fullBoard').onchange = (ev) => { state.full = ev.target.checked; renderBoard(); };
$('helpBtn').onclick = () => $('help').showModal();

init();
