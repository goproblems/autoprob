// the crawl's suggestions, reviewed one by one before any goes to the site (CrawlReviewServer)
const S = {entries: [], counts: {}, filter: 'pending', name: null, item: null,
  tree: null, sel: null, choice: new Map(), wrong: true};
const TABS = ['pending', 'approved', 'rejected', 'sent', 'all'];
try {
  S.wrong = localStorage.getItem('crawl.wrongLines') !== 'hide';
} catch (e) { /* no storage: the wrong lines show */ }

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

// a board as svg: rows of X, O and ., top first; marks {x, y, kind, label}, and for a next
// move in the tree its status, node and title
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
    if (m.kind === 'next') {
      s += `<g class="next" data-i="${m.i}"><title>${esc(m.title)}</title>
        <circle cx="${X(m.x)}" cy="${Y(m.y)}" r="${c * 0.34}" class="mark-next ${m.status}"/>
        <circle cx="${X(m.x)}" cy="${Y(m.y)}" r="${c * 0.5}" fill="transparent"/></g>`;
    }
    if (m.label) s += `<text x="${X(m.x)}" y="${Y(m.y) + 4}" class="label ${on}">${m.label}</text>`;
  }
  return s + '</svg>';
}

async function select(name) {
  if (!name) return;
  const other = name !== S.name;
  S.name = name;
  try {
    S.item = await api('/api/item?name=' + encodeURIComponent(name));
  } catch (e) {
    $('item').innerHTML = `<p class="error">${esc(e.message)}</p>`;
    return;
  }
  renderItem();
  renderList();
  // another suggestion starts at its top, with the tree
  if (other) $('item').scrollTop = 0;
}

function renderItem() {
  const it = S.item, d = it.draft, a = it.approval, s = it.submitted;
  const where = s ? `sent as suggestion #${s.suggestionId}${s.status ? ` (${s.status})` : ''}`
    : a ? `${a.decision} by ${esc(a.by)}` : 'waiting for review';
  let h = `<h2><a href="${it.url}" target="_blank" rel="noopener">${d.problem}</a> <small>${esc(d.difficulty)} · ${where}</small></h2>`;
  h += it.tree ? `<section class="place" id="tree-section">
      <h3>The tree <label class="wrong-toggle" title="Show the problem's wrong lines too"><input type="checkbox" id="wrong-lines" ${S.wrong ? 'checked' : ''}> wrong lines</label></h3>
      <p class="legend"><span class="sw right"></span> correct <span class="sw added"></span> added
        <span class="sw changed"></span> a wrong line made correct <span class="sw wrong"></span> wrong
        <span class="sw end"></span> ends a correct line</p>
      <div class="tree-wrap"><figure id="tree-board"></figure><div id="tree" class="tree"></div></div>
      <p id="tree-note" class="tree-note"></p>
    </section>` : `<p class="error">The tree could not be read: ${esc(it.treeError)}</p>`;
  it.places.forEach((p, i) => {
    const marks = p.moves.map(([x, y]) => ({x, y, kind: 'new'})).concat(p.accepted.map(([x, y]) => ({x, y, kind: 'accepted'})));
    h += `<section class="place">
      <h3>${p.at.length ? 'After ' + esc(p.at.join(', ')) : 'At the start'}
        ${it.tree ? `<button class="to-tree" data-place="${i}" title="Select this position in the tree">in the tree</button>` : ''}</h3>
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
  S.tree = it.tree ? buildTree(it.tree) : null;
  S.choice = new Map();
  if (S.tree) {
    // to start with, the first position the suggestion adds lines at, the arrow keys walking
    // the tree, and the tree scrolled to the first move added there: the problem's own moves
    // come first, so it can be far below
    const start = (it.places.length && nodeAt(it.places[0].at)) || S.tree[0];
    selectNode(start);
    if (chosen(start)?.adds) reveal(chosen(start), true);
    $('wrong-lines').onchange = e => showWrong(e.target.checked);
    for (const b of $('item').querySelectorAll('.to-tree')) b.onclick = () => {
      const n = nodeAt(it.places[+b.dataset.place].at);
      if (!n) return;
      if (!S.wrong && n.status === 'wrong') showWrong(true);
      selectNode(n);
      $('tree-section').scrollIntoView({block: 'nearest', behavior: 'smooth'});
    };
  }
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
    focusFigure(f);
    showStep(i, j, +b.dataset.k);
  };
}

// the figure the arrow keys act on: a line, or the tree's board
function focusFigure(f) {
  for (const g of $('item').querySelectorAll('figure')) g.classList.toggle('on', g === f);
}

// the problem's tree as the suggestion leaves it, from the server's nodes (each after its
// parent): each node gets its children and depth, and says whether a correct end lies below
// it now (leads) and in the problem as the site has it (led), and whether the suggestion adds
// anything there or below (adds). its status colors the line to it: added, changed (the
// problem's own move on a wrong line, which the suggestion carries on to a correct end),
// right or wrong
function buildTree(list) {
  const nodes = list.map((n, i) => ({...n, i, children: [], depth: 0}));
  for (const n of nodes) {
    n.parent = n.parent < 0 ? null : nodes[n.parent];
    if (!n.parent) continue;
    n.depth = n.parent.depth + 1;
    n.parent.children.push(n);
  }
  for (let i = nodes.length - 1; i >= 0; i--) {
    const n = nodes[i];
    n.leads = n.right || n.children.some(c => c.leads);
    n.led = !n.added && (n.right || n.children.some(c => c.led));
    n.adds = n.added || n.children.some(c => c.adds);
    n.status = n.added ? 'added' : n.leads && !n.led ? 'changed' : n.leads ? 'right' : 'wrong';
  }
  return nodes;
}

const STATUS = {
  added: 'added by the suggestion',
  changed: 'the problem has it on a wrong line, which the suggestion carries on to a correct end',
  right: 'on a correct line',
  wrong: 'on a wrong line',
};

// the children shown: all, or with the wrong lines hidden, those a correct line goes through
const kids = n => S.wrong ? n.children : n.children.filter(c => c.status !== 'wrong');

// the child ▶ and → go to: the one picked with ↑/↓, else the first move the suggestion adds
// (or carries on) here, else the first it adds something below, else the first
function chosen(n) {
  const k = kids(n), c = S.choice.get(n);
  return k.includes(c) ? c : k.find(c => c.status === 'added' || c.status === 'changed')
    || k.find(c => c.adds) || k[0] || null;
}

// the node the moves ("B E12") reach from the start, or null
function nodeAt(moves) {
  let n = S.tree[0];
  for (const m of moves) if (!(n = n.children.find(c => c.move === m))) return null;
  return n;
}

function tip(n) {
  if (!n.parent) return 'the start' + (n.comment ? ': ' + n.comment : '');
  const t = [`${n.depth}. ${n.move ?? 'no move'}`, STATUS[n.status]];
  if (n.right) t.push('ends a correct line');
  if (n.comment) t.push(n.comment.length > 160 ? n.comment.slice(0, 160) + '…' : n.comment);
  return t.join(' · ');
}

// selects a node of the tree, keeping the current line through it, and makes the arrow keys
// walk the tree
function selectNode(n) {
  S.sel = n;
  for (let c = n; c.parent; c = c.parent) S.choice.set(c.parent, c);
  focusFigure($('tree-board'));
  renderTree();
}

// ↑/↓: which branch ▶ follows from the selected node
function pickBranch(step) {
  const k = kids(S.sel);
  if (k.length < 2) return;
  const i = k.indexOf(chosen(S.sel));
  S.choice.set(S.sel, k[Math.max(0, Math.min(k.length - 1, i + step))]);
  renderTree();
}

function showWrong(on) {
  S.wrong = on;
  try {
    localStorage.setItem('crawl.wrongLines', on ? 'show' : 'hide');
  } catch (e) { /* kept for this page only */ }
  $('wrong-lines').checked = on;
  while (!on && S.sel.parent && S.sel.status === 'wrong') S.sel = S.sel.parent;
  renderTree();
}

// the tree's spacing: from move to move, from line to line, a stone's radius, the margin
const DX = 30, DY = 26, R = 8.5, PAD = 16;
const treeX = n => PAD + n.depth * DX, treeY = row => PAD + row * DY;

// the tree, laid out like the redo review's (and the site's): depth runs right, branches stack
// downward, and a branch drops straight down from its parent before slanting in. the current
// line, from the start to the selected node and on through the chosen children, is thick
function renderTree() {
  const shown = [], sky = [];
  const place = (n, row) => {
    n.row = row;
    shown.push(n);
    sky[n.depth] = Math.max(sky[n.depth] ?? -1, row);
    kids(n).forEach((c, i) => {
      let r = row;
      if (i > 0) {
        // clear every column the branch's main line runs through, and keep the parent's
        // column clear down to it for the drop
        let lowest = -1;
        for (let m = c; m; m = kids(m)[0]) lowest = Math.max(lowest, sky[m.depth] ?? -1);
        r = Math.max(row + 1, lowest + 1);
        sky[n.depth] = Math.max(sky[n.depth], r - 1);
      }
      place(c, r);
    });
  };
  place(S.tree[0], 0);
  const w = PAD * 2 + Math.max(...shown.map(n => n.depth)) * DX, h = PAD * 2 + Math.max(...shown.map(n => n.row)) * DY;
  const line = new Set();
  for (let n = S.sel; n; n = n.parent) line.add(n);
  for (let n = chosen(S.sel); n; n = chosen(n)) line.add(n);
  let s = `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="tree" aria-label="the problem's tree">`;
  // the lines under the stones, the current line over the others
  for (const n of shown.filter(n => n.parent).sort((a, b) => line.has(a) - line.has(b))) {
    const cls = `edge ${n.status}${line.has(n) ? ' thick' : ''}`;
    const px = treeX(n.parent), py = treeY(n.parent.row), x = treeX(n), y = treeY(n.row);
    s += n.row === n.parent.row ? `<line class="${cls}" x1="${px}" y1="${py}" x2="${x}" y2="${y}"/>`
      : `<polyline class="${cls}" points="${px},${py} ${px},${treeY(n.row - 1)} ${x},${y}"/>`;
  }
  for (const n of shown) {
    const x = treeX(n), y = treeY(n.row);
    s += `<g class="tnode" data-i="${n.i}"><title>${esc(tip(n))}</title>`;
    if (n === S.sel) s += `<rect x="${x - 12}" y="${y - 12}" width="24" height="24" rx="3" class="tsel"/>`;
    if (n.right) s += `<circle cx="${x}" cy="${y}" r="${R * 1.35}" class="halo ${n.added ? 'added' : 'right'}"/>`;
    if (!n.parent) s += `<path d="M${x - 7} ${y + 6}V${y - 1}L${x} ${y - 7}L${x + 7} ${y - 1}V${y + 6}Z" class="house"/>`;
    else if (n.x == null) s += `<circle cx="${x}" cy="${y}" r="${R * 0.6}" class="tpass"/>`;
    else {
      // a faded stone on a disc of the background, so the lines do not show through it
      if (n.status === 'wrong' && n !== S.sel) s += `<circle cx="${x}" cy="${y}" r="${R}" class="tbg"/>`;
      s += `<circle cx="${x}" cy="${y}" r="${R}" class="tstone ${n.move[0] === 'B' ? 'b' : 'w'} ${n.status}"/>`;
    }
    s += `<circle cx="${x}" cy="${y}" r="${R + 4}" fill="transparent"/></g>`;
  }
  const box = $('tree');
  box.innerHTML = s + '</svg>';
  for (const g of box.querySelectorAll('.tnode')) g.onclick = () => selectNode(S.tree[+g.dataset.i]);
  renderTreeBoard();
  box.style.maxHeight = $('tree-board').offsetHeight + 'px';
  reveal(S.sel);
}

// the board at the selected node: its move ringed, and the moves that can come next circled
// in the colors of their lines, to click
function renderTreeBoard() {
  const n = S.sel, next = chosen(n), f = $('tree-board');
  const marks = kids(n).filter(c => c.x != null && n.rows[c.y][c.x] === '.')
    .map(c => ({x: c.x, y: c.y, kind: 'next', status: c.status, i: c.i, title: tip(c)}));
  if (n.x != null) marks.push({x: n.x, y: n.y, kind: 'last'});
  f.innerHTML = board(n.rows, S.item.columns, marks)
    + `<figcaption class="step"><button title="Back a move (←)" ${n.parent ? '' : 'disabled'}>◀</button>
       <span>${n.parent ? esc(`${n.depth}. ${n.move ?? '—'}`) : 'start'}</span>
       <button title="On along the thick line (→; ↑/↓ pick the branch)" ${next ? '' : 'disabled'}>▶</button></figcaption>`;
  const [back, on] = f.querySelectorAll('button');
  back.onclick = () => selectNode(n.parent);
  on.onclick = () => selectNode(next);
  for (const g of f.querySelectorAll('.next')) g.onclick = () => selectNode(S.tree[+g.dataset.i]);
  $('tree-note').innerHTML = (n.parent ? esc(`${n.move ?? 'No move'}: ${STATUS[n.status]}${n.right ? ', and ends a correct line' : ''}.`)
    : 'The start.') + (n.comment ? ` <q>${esc(n.comment)}</q>` : '');
}

// scrolls the tree, and only the tree, to keep the node in view; with lines below, to show
// them too, the node near the top unless it is in the upper half already
function reveal(n, below) {
  const box = $('tree'), x = treeX(n), y = treeY(n.row), m = 24;
  if (x - m < box.scrollLeft) box.scrollLeft = x - m;
  else if (x + m > box.scrollLeft + box.clientWidth) box.scrollLeft = x + m - box.clientWidth;
  if (below && (y < box.scrollTop || y > box.scrollTop + box.clientHeight / 2)) box.scrollTop = y - box.clientHeight / 4;
  else if (y - m < box.scrollTop) box.scrollTop = y - m;
  else if (y + m > box.scrollTop + box.clientHeight) box.scrollTop = y + m - box.clientHeight;
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
  if (e.target.matches('textarea, input:not([type=checkbox])') || e.ctrlKey || e.metaKey || e.altKey || !S.item) return;
  if (e.key === 'a' && !$('approve').disabled) decide('approved');
  else if (e.key === 'r' && !$('reject').disabled) decide('rejected');
  else if (e.key === 'j') move(1);
  else if (e.key === 'k') move(-1);
  else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    const f = $('item').querySelector('figure.on');
    if (!f) return;
    e.preventDefault();
    f.querySelectorAll('button')[e.key === 'ArrowLeft' ? 0 : 1].click();
  } else if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && $('tree-board')?.classList.contains('on')) {
    e.preventDefault();
    pickBranch(e.key === 'ArrowUp' ? -1 : 1);
  }
});

window.onhashchange = () => select(location.hash.slice(1));
loadList().then(() => {
  if (location.hash) select(location.hash.slice(1));
  else if (visible().length) location.hash = visible()[0].name;
});
