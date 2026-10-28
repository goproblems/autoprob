// the crawl's suggestions, reviewed one by one before any goes to the site (CrawlReviewServer)
const S = {entries: [], counts: {}, filter: 'pending', name: null, item: null};
const TABS = ['pending', 'approved', 'rejected', 'sent', 'all'];

const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));

async function api(path, opts) {
  const r = await fetch(path, opts);
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || r.status);
  return j;
}

function conf(c) {
  return c ? `${c.label} ${c.low}-${c.high}%` : 'no confidence';
}

async function loadList() {
  const d = await api('/api/list');
  S.entries = d.entries;
  S.counts = d.counts;
  renderTabs();
  renderList();
}

function visible() {
  return S.entries.filter(e => S.filter === 'all' || e.state === S.filter);
}

function renderTabs() {
  $('tabs').innerHTML = TABS.map(t => {
    const n = t === 'all' ? S.entries.length : (S.counts[t] || 0);
    return `<button data-tab="${t}" class="${S.filter === t ? 'on' : ''}">${t} <span>${n}</span></button>`;
  }).join('');
  for (const b of $('tabs').querySelectorAll('button')) {
    b.onclick = () => { S.filter = b.dataset.tab; renderTabs(); renderList(); };
  }
}

function renderList() {
  const items = visible();
  $('list').innerHTML = items.length ? items.map(e => `
    <a href="#${e.name}" class="row ${e.state} ${e.name === S.name ? 'on' : ''}">
      <b>${e.name}</b> <span>${esc(e.difficulty)}</span>
      <small>${esc(e.moves.join(', '))}${e.positions > 1 ? ` · ${e.positions} positions` : ''} · ${conf(e.confidence)}</small>
    </a>`).join('') : '<p class="empty">nothing here</p>';
}

// a board as svg: rows of X, O and ., top first; marks {x, y, kind, label}
function board(rows, cols, marks = []) {
  const n = rows.length, c = 26, pad = 24, size = pad * 2 + c * (n - 1);
  const X = x => pad + x * c, Y = y => pad + y * c;
  let s = `<svg viewBox="0 0 ${size} ${size}" width="${size}" height="${size}" class="goban">`;
  s += `<rect width="${size}" height="${size}" class="wood"/>`;
  for (let i = 0; i < n; i++) {
    s += `<line x1="${X(0)}" y1="${Y(i)}" x2="${X(n - 1)}" y2="${Y(i)}"/><line x1="${X(i)}" y1="${Y(0)}" x2="${X(i)}" y2="${Y(n - 1)}"/>`;
    s += `<text x="${X(i)}" y="${pad - 12}" class="coord">${cols[i]}</text><text x="${pad - 14}" y="${Y(i) + 4}" class="coord">${n - i}</text>`;
  }
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const v = rows[y][x];
    if (v === 'X') s += `<circle cx="${X(x)}" cy="${Y(y)}" r="${c * 0.46}" class="black"/>`;
    if (v === 'O') s += `<circle cx="${X(x)}" cy="${Y(y)}" r="${c * 0.46}" class="white"/>`;
  }
  for (const m of marks) {
    const v = rows[m.y][m.x], on = v === 'X' ? 'on-black' : v === 'O' ? 'on-white' : '';
    if (m.kind === 'new') s += `<rect x="${X(m.x) - c * 0.3}" y="${Y(m.y) - c * 0.3}" width="${c * 0.6}" height="${c * 0.6}" class="mark-new"/>`;
    if (m.kind === 'accepted') s += `<circle cx="${X(m.x)}" cy="${Y(m.y)}" r="${c * 0.22}" class="mark-accepted"/>`;
    if (m.kind === 'last') s += `<circle cx="${X(m.x)}" cy="${Y(m.y)}" r="${c * 0.3}" class="mark-last ${on}"/>`;
    if (m.label) s += `<text x="${X(m.x)}" y="${Y(m.y) + 4}" class="label ${on}">${m.label}</text>`;
  }
  return s + '</svg>';
}

async function select(name) {
  if (!name) return;
  S.name = name;
  try {
    S.item = await api('/api/item?name=' + encodeURIComponent(name));
  } catch (e) {
    $('item').innerHTML = `<p class="error">${esc(e.message)}</p>`;
    return;
  }
  renderItem();
  renderList();
}

function renderItem() {
  const it = S.item, d = it.draft, a = it.approval, s = it.submitted;
  const where = s ? `sent as suggestion #${s.suggestionId}${s.status ? ` (${s.status})` : ''}`
    : a ? `${a.decision} by ${esc(a.by)}` : 'waiting for review';
  let h = `<h2><a href="${it.url}" target="_blank" rel="noopener">${d.problem}</a> <small>${esc(d.difficulty)} · ${where}</small></h2>`;
  it.places.forEach((p, i) => {
    const marks = p.moves.map(([x, y]) => ({x, y, kind: 'new'})).concat(p.accepted.map(([x, y]) => ({x, y, kind: 'accepted'})));
    h += `<section class="place">
      <h3>${p.at.length ? 'After ' + esc(p.at.join(', ')) : 'At the start'}</h3>
      <p class="legend"><span class="key-new"></span> missing (${esc(p.lines.map(l => l.moves[0]).join(', '))})
        <span class="key-accepted"></span> the problem accepts ${p.level ? `· ${p.level.toLowerCase()}` : ''}</p>
      <div class="boards">
        <figure>${board(p.rows, it.columns, marks)}<figcaption>the position</figcaption></figure>
        ${p.lines.map((l, j) => `<figure id="line-${i}-${j}"></figure>`).join('')}
      </div>
    </section>`;
  });
  const c = d.places.map(p => p.confidence).filter(Boolean).sort((x, y) => x.low - y.low)[0];
  if (c) {
    h += `<section class="confidence"><h3>Confidence: ${esc(c.label)} (${c.low}-${c.high}%)</h3><ul>${c.reasons.map(r =>
      `<li class="${r.startsWith('+') ? 'for' : 'against'}">${esc(r.slice(2))}</li>`).join('')}</ul></section>`;
  }
  const locked = !!s;
  h += `<section class="decide">
    <label>Comment sent with the suggestion<textarea id="comment" rows="4" ${locked ? 'disabled' : ''}>${esc(a?.comment || d.comment)}</textarea></label>
    <label>Note, kept here<input id="note" value="${esc(a?.note)}" ${locked ? 'disabled' : ''}></label>
    <div class="buttons">
      <button id="approve" class="approve" ${locked ? 'disabled' : ''}>Approve</button>
      <button id="reject" class="reject" ${locked ? 'disabled' : ''}>Reject</button>
      <button id="pending" ${locked || !a ? 'disabled' : ''}>Back to pending</button>
      <button id="next">Next</button>
    </div>
    <p id="status" class="status"></p>
  </section>`;
  $('item').innerHTML = h;
  it.places.forEach((p, i) => p.lines.forEach((l, j) => showStep(i, j, l.steps.length)));
  $('approve').onclick = () => decide('approved');
  $('reject').onclick = () => decide('rejected');
  $('pending').onclick = () => decide('pending');
  $('next').onclick = () => move(1);
}

// a line added at a position, after its k-th move, with buttons to step through it. Stepping
// selects the line, and then the arrow keys step it too
function showStep(i, j, k) {
  const p = S.item.places[i], l = p.lines[j];
  const rows = k === 0 ? p.rows : l.steps[k - 1].rows;
  const marks = [];
  l.steps.slice(0, k).forEach((st, n) => {
    if (rows[st.y][st.x] !== '.') marks.push({x: st.x, y: st.y, label: n + 1});
  });
  if (k > 0) marks.push({x: l.steps[k - 1].x, y: l.steps[k - 1].y, kind: 'last'});
  const f = $(`line-${i}-${j}`);
  f.innerHTML = board(rows, S.item.columns, marks)
    + `<figcaption class="step"><button data-k="${Math.max(0, k - 1)}">◀</button>
       <span>${k === 0 ? 'line' : esc(l.moves[k - 1])}${k === l.steps.length ? ' (right)' : ''}</span>
       <button data-k="${Math.min(l.steps.length, k + 1)}">▶</button></figcaption>`;
  for (const b of f.querySelectorAll('button')) b.onclick = () => {
    for (const g of $('item').querySelectorAll('figure')) g.classList.toggle('on', g === f);
    showStep(i, j, +b.dataset.k);
  };
}

async function decide(decision) {
  const body = {decision, comment: $('comment').value, note: $('note').value};
  try {
    await api('/api/decide?name=' + encodeURIComponent(S.name), {method: 'POST', body: JSON.stringify(body)});
  } catch (e) {
    $('status').textContent = e.message;
    return;
  }
  const done = S.name;
  await loadList();
  if (decision === 'pending') return select(done);
  move(1, done);
}

// the next (or previous) suggestion in the list as it is shown
function move(dir, from = S.name) {
  const items = visible();
  if (!items.length) { $('item').innerHTML = '<p class="empty">nothing left here</p>'; return; }
  let i = items.findIndex(e => e.name === from);
  i = i < 0 ? 0 : Math.max(0, Math.min(items.length - 1, i + dir));
  location.hash = items[i].name;
  if (items[i].name === S.name) select(S.name);
}

document.addEventListener('keydown', e => {
  if (e.target.matches('textarea, input') || e.ctrlKey || e.metaKey || e.altKey || !S.item) return;
  if (e.key === 'a' && !$('approve').disabled) decide('approved');
  else if (e.key === 'r' && !$('reject').disabled) decide('rejected');
  else if (e.key === 'j') move(1);
  else if (e.key === 'k') move(-1);
  else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    const f = $('item').querySelector('figure.on');
    if (!f) return;
    e.preventDefault();
    f.querySelectorAll('button')[e.key === 'ArrowLeft' ? 0 : 1].click();
  }
});

window.onhashchange = () => select(location.hash.slice(1));
loadList().then(() => {
  if (location.hash) select(location.hash.slice(1));
  else if (visible().length) location.hash = visible()[0].name;
});
