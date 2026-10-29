// the crawl's suggestions, reviewed one by one before any goes to the site (CrawlReviewServer)
const S = {entries: [], counts: {}, filter: 'pending', name: null, item: null,
  tree: null, sel: null, choice: new Map(), wrong: true, adding: false};
const TABS = ['pending', 'approved', 'rejected', 'sent', 'all'];
const SGF = 'abcdefghijklmnopqrs';
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
      <small>${esc(e.moves.join(', '))}${e.positions > 1 ? ` · ${e.positions} positions` : ''} · ${conf(e.confidence)}${e.edited ? ' · edited' : ''}</small>
    </a>`).join('') : '<p class="empty">nothing here</p>';
}

// a board's spacing: from line to line, and from the edge to the first line
const CELL = 26, EDGE = 24;

// a board as svg: rows of X, O and ., top first; marks {x, y, kind, label}, and for a next
// move in the tree its status, node and title
function board(rows, cols, marks = []) {
  const n = rows.length, c = CELL, pad = EDGE, size = pad * 2 + c * (n - 1);
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

// the board after a move: rows of X, O and ., and the point of a ko it takes, or null when the
// point is taken or the move would leave its own stones without a liberty
function play(rows, x, y, color) {
  const n = rows.length, b = rows.map(r => [...r]);
  if (b[y][x] !== '.') return null;
  const me = color === 'B' ? 'X' : 'O', them = me === 'X' ? 'O' : 'X';
  const around = (px, py) => [[px + 1, py], [px - 1, py], [px, py + 1], [px, py - 1]]
    .filter(([ax, ay]) => ax >= 0 && ay >= 0 && ax < n && ay < n);
  // the stones of the group at the point, and how many liberties it has
  const group = (gx, gy) => {
    const color = b[gy][gx], seen = new Set([gx + ',' + gy]), stones = [[gx, gy]], libs = new Set();
    for (let k = 0; k < stones.length; k++) {
      for (const [ax, ay] of around(...stones[k])) {
        if (b[ay][ax] === '.') libs.add(ax + ',' + ay);
        else if (b[ay][ax] === color && !seen.has(ax + ',' + ay)) {
          seen.add(ax + ',' + ay);
          stones.push([ax, ay]);
        }
      }
    }
    return {stones, libs: libs.size};
  };
  b[y][x] = me;
  const taken = [];
  for (const [ax, ay] of around(x, y)) {
    if (b[ay][ax] !== them) continue;
    const g = group(ax, ay);
    if (g.libs === 0) for (const [sx, sy] of g.stones) {
      b[sy][sx] = '.';
      taken.push([sx, sy]);
    }
  }
  const own = group(x, y);
  if (own.libs === 0) return null;
  // one stone taking one, with no liberty but the point it took: the other side may not take
  // back there at once
  const ko = taken.length === 1 && own.stones.length === 1 && own.libs === 1 ? taken[0] : null;
  return {rows: b.map(r => r.join('')), ko};
}

async function select(name, path) {
  if (!name) return;
  const other = name !== S.name;
  if (unsaved() && !confirm(`Drop the edits to ${S.name}'s tree, not saved yet?`)) {
    if (other) history.replaceState(null, '', '#' + S.name);
    return;
  }
  S.name = name;
  try {
    S.item = await api('/api/item?name=' + encodeURIComponent(name));
  } catch (e) {
    $('item').innerHTML = `<p class="error">${esc(e.message)}</p>`;
    return;
  }
  renderItem(path);
  renderList();
  // another suggestion starts at its top, with the tree
  if (other) $('item').scrollTop = 0;
}

// the suggestion on the page; path, the moves to a node of the tree to select, as after its
// edits are saved
function renderItem(path) {
  const it = S.item, d = it.draft, a = it.approval, s = it.submitted;
  const where = s ? `sent as suggestion #${s.suggestionId}${s.status ? ` (${s.status})` : ''}`
    : a ? `${a.decision} by ${esc(a.by)}` : 'waiting for review';
  let h = `<h2><a href="${it.url}" target="_blank" rel="noopener">${d.problem}</a> <small>${esc(d.difficulty)} · ${where}</small></h2>`;
  h += it.tree ? `<section class="place" id="tree-section">
      <h3>The tree ${it.edited ? `<small class="edited" title="${esc(it.edited.time)}">edited by ${esc(it.edited.by)}</small>` : ''}
        <label class="wrong-toggle" title="Show the problem's wrong lines too"><input type="checkbox" id="wrong-lines" ${S.wrong ? 'checked' : ''}> wrong lines</label></h3>
      <p class="legend"><span class="sw right"></span> correct <span class="sw added"></span> added
        <span class="sw changed"></span> a wrong line made correct <span class="sw wrong"></span> wrong
        <span class="sw end"></span> ends a correct line <span class="sw edit"></span> edits not saved</p>
      <div class="tree-wrap"><figure id="tree-board"></figure><div id="tree" class="tree"></div></div>
      <div id="tree-edit" class="tree-edit"></div>
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
  S.adding = false;
  if (S.tree) {
    // to start with, the first position the suggestion adds lines at, the arrow keys walking
    // the tree, and the tree scrolled to the first move added there: the problem's own moves
    // come first, so it can be far below
    const kept = path && deepest(path);
    const start = kept || (it.places.length && nodeAt(it.places[0].at)) || S.tree[0];
    selectNode(start);
    if (!kept && chosen(start)?.adds) reveal(chosen(start), true);
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
// parent), each with its children and depth
function buildTree(list) {
  const nodes = list.map((n, i) => ({...n, i, children: [], depth: 0}));
  for (const n of nodes) {
    n.parent = n.parent < 0 ? null : nodes[n.parent];
    if (!n.parent) continue;
    n.depth = n.parent.depth + 1;
    n.parent.children.push(n);
  }
  statuses(nodes);
  return nodes;
}

// edits not saved yet sit on the nodes: fresh, a move added; cut, a branch cut there, its
// nodes ghosts until saved; pendRight, a right mark put on or taken off. a fresh node cut goes
// at once (gone)
const rightNow = n => n.pendRight ?? n.right;

// what each node's line comes to, its status, with the edits not saved yet, and as saved
// (was): added, fresh, ghost, changed (the problem's own move on a wrong line, which the
// suggestion carries on to a correct end), right or wrong. leads: a correct end lies below it;
// led: in the problem as the site has it; adds: the suggestion adds something there or below
function statuses(nodes = S.tree) {
  for (const n of nodes) n.ghost = !!n.cut || !!n.parent?.ghost;
  for (let i = nodes.length - 1; i >= 0; i--) {
    const n = nodes[i], live = n.children.filter(c => !c.ghost);
    n.leads = !n.ghost && (rightNow(n) || live.some(c => c.leads));
    n.leadsWas = !n.fresh && (n.right || n.children.some(c => c.leadsWas));
    n.led = !n.added && !n.fresh && (n.right || n.children.some(c => c.led));
    n.adds = !n.ghost && (n.added || !!n.fresh || live.some(c => c.adds));
    n.was = n.fresh ? null : n.added ? 'added' : n.leadsWas && !n.led ? 'changed' : n.leadsWas ? 'right' : 'wrong';
    n.status = n.ghost ? 'ghost' : n.fresh ? 'fresh' : n.added ? 'added' : n.leads && !n.led ? 'changed' : n.leads ? 'right' : 'wrong';
  }
}

// whether an edit not saved yet makes a saved node's line come to something else
const restyled = n => !n.fresh && !n.ghost && n.status !== n.was;

const STATUS = {
  added: 'added by the suggestion',
  fresh: 'added by an edit, not saved yet',
  ghost: 'cut by an edit, not saved yet',
  changed: 'the problem has it on a wrong line, which the suggestion carries on to a correct end',
  right: 'on a correct line',
  wrong: 'on a wrong line',
};

// the children shown: all, or with the wrong lines hidden, those a correct line goes through
const kids = n => S.wrong ? n.children : n.children.filter(c => c.status !== 'wrong');

// the child ▶ and → go to: the one picked with ↑/↓, else the first move the suggestion adds
// (or carries on) here, else the first it adds something below, else the first not cut
function chosen(n) {
  const k = kids(n), c = S.choice.get(n);
  return k.includes(c) ? c : k.find(c => ['added', 'changed', 'fresh'].includes(c.status))
    || k.find(c => c.adds) || k.find(c => !c.ghost) || k[0] || null;
}

// the node the moves ("B E12") reach from the start, or null
function nodeAt(moves) {
  let n = S.tree[0];
  for (const m of moves) if (!(n = n.children.find(c => c.move === m))) return null;
  return n;
}

// the node the moves reach, or the deepest one on their way there
function deepest(moves) {
  let n = S.tree[0];
  for (const m of moves) {
    const c = n.children.find(c => c.move === m);
    if (!c) break;
    n = c;
  }
  return n;
}

// the moves to the node from the start, as shown ("B E12") or as sgf ("B ec")
const pathOf = n => n.parent ? [...pathOf(n.parent), n.move] : [];
const sgfOf = n => `${n.move[0]} ${SGF[n.x]}${SGF[n.y]}`;
const sgfPath = n => n.parent ? [...sgfPath(n.parent), sgfOf(n)] : [];

function tip(n) {
  if (!n.parent) return 'the start' + (n.comment ? ': ' + n.comment : '');
  const t = [`${n.depth}. ${n.move ?? 'no move'}`, STATUS[n.status]];
  if (rightNow(n)) t.push(n.pendRight ? 'marked right, not saved yet' : 'ends a correct line');
  else if (n.pendRight === false) t.push('right mark taken off, not saved yet');
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
// line, from the start to the selected node and on through the chosen children, is thick.
// edits not saved yet are light blue
function renderTree() {
  statuses();
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
    const cls = `edge ${n.status}${restyled(n) ? ' pend' : ''}${line.has(n) ? ' thick' : ''}`;
    const px = treeX(n.parent), py = treeY(n.parent.row), x = treeX(n), y = treeY(n.row);
    s += n.row === n.parent.row ? `<line class="${cls}" x1="${px}" y1="${py}" x2="${x}" y2="${y}"/>`
      : `<polyline class="${cls}" points="${px},${py} ${px},${treeY(n.row - 1)} ${x},${y}"/>`;
  }
  for (const n of shown) {
    const x = treeX(n), y = treeY(n.row);
    s += `<g class="tnode" data-i="${n.i}"><title>${esc(tip(n))}</title>`;
    if (n === S.sel) s += `<rect x="${x - 12}" y="${y - 12}" width="24" height="24" rx="3" class="tsel"/>`;
    if (rightNow(n) && !n.ghost) {
      s += `<circle cx="${x}" cy="${y}" r="${R * 1.35}" class="halo ${n.pendRight !== undefined ? 'pend' : n.added ? 'added' : 'right'}"/>`;
    } else if (n.right && n.pendRight === false) {
      s += `<circle cx="${x}" cy="${y}" r="${R * 1.35}" class="unhalo"/>`;
    }
    if (!n.parent) s += `<path d="M${x - 7} ${y + 6}V${y - 1}L${x} ${y - 7}L${x + 7} ${y - 1}V${y + 6}Z" class="house"/>`;
    else if (n.x == null) s += `<circle cx="${x}" cy="${y}" r="${R * 0.6}" class="tpass"/>`;
    else {
      // a faded stone on a disc of the background, so the lines do not show through it
      if ((n.status === 'wrong' || n.ghost) && n !== S.sel) s += `<circle cx="${x}" cy="${y}" r="${R}" class="tbg"/>`;
      s += `<circle cx="${x}" cy="${y}" r="${R}" class="tstone ${n.move[0] === 'B' ? 'b' : 'w'} ${n.status}"/>`;
    }
    s += `<circle cx="${x}" cy="${y}" r="${R + 4}" fill="transparent"/></g>`;
  }
  const box = $('tree');
  box.innerHTML = s + '</svg>';
  for (const g of box.querySelectorAll('.tnode')) g.onclick = () => selectNode(S.tree[+g.dataset.i]);
  renderTreeBoard();
  renderEditBar();
  box.style.maxHeight = $('tree-board').offsetHeight + 'px';
  reveal(S.sel);
}

// the board at the selected node: its move ringed, and the moves that can come next circled
// in the colors of their lines, to click. while adding moves, a click on an empty point adds one
function renderTreeBoard() {
  const n = S.sel, next = chosen(n), f = $('tree-board');
  const marks = kids(n).filter(c => c.x != null && n.rows[c.y][c.x] === '.')
    .map(c => ({x: c.x, y: c.y, kind: 'next', status: c.status + (restyled(c) ? ' pend' : ''), i: c.i, title: tip(c)}));
  if (n.x != null) marks.push({x: n.x, y: n.y, kind: 'last'});
  f.innerHTML = board(n.rows, S.item.columns, marks)
    + `<figcaption class="step"><button title="Back a move (←)" ${n.parent ? '' : 'disabled'}>◀</button>
       <span>${n.parent ? esc(`${n.depth}. ${n.move ?? '—'}`) : 'start'}</span>
       <button title="On along the thick line (→; ↑/↓ pick the branch)" ${next ? '' : 'disabled'}>▶</button></figcaption>`;
  const [back, on] = f.querySelectorAll('button');
  back.onclick = () => selectNode(n.parent);
  on.onclick = () => selectNode(next);
  for (const g of f.querySelectorAll('.next')) g.onclick = () => selectNode(S.tree[+g.dataset.i]);
  const svg = f.querySelector('svg');
  svg.classList.toggle('adding', S.adding);
  svg.onclick = ev => {
    if (!S.adding || ev.target.closest('.next')) return;
    const p = new DOMPoint(ev.clientX, ev.clientY).matrixTransform(svg.getScreenCTM().inverse());
    const x = Math.round((p.x - EDGE) / CELL), y = Math.round((p.y - EDGE) / CELL);
    if (x >= 0 && y >= 0 && x < n.rows.length && y < n.rows.length) addMove(x, y);
  };
  $('tree-note').innerHTML = (n.parent ? esc(`${n.move ?? 'No move'}: ${STATUS[n.status]}${rightNow(n) ? ', and ends a correct line' : ''}.`)
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

// ---- editing the tree by hand: moves the suggestion adds can be marked right or cut, and
// moves added anywhere; nothing changes for the site until it is saved

// the edits not saved yet: moves added, branches cut, right marks put on or taken off
function changes() {
  const c = {added: 0, cut: 0, marks: 0, total: 0};
  for (const n of S.tree || []) {
    if (n.gone) continue;
    if (n.fresh && !n.ghost) c.added++;
    if (n.cut && !n.parent.ghost) c.cut++;
    if (!n.fresh && !n.ghost && n.pendRight !== undefined) c.marks++;
  }
  c.total = c.added + c.cut + c.marks;
  return c;
}
const unsaved = () => changes().total > 0;

// why the suggestion cannot be edited, or null: only one waiting for review can be, so that a
// decision is always on what it decided
function locked() {
  const it = S.item;
  return it.submitted ? 'It went to the site, so it cannot be edited.'
    : it.approval ? `It is ${it.approval.decision}: take it back to pending to edit it.` : null;
}

function renderEditBar() {
  const n = S.sel, ch = changes(), why = locked();
  const mine = n.added || n.fresh;
  const plural = (k, one, many) => `${k} ${k === 1 ? one : many}`;
  const said = [];
  if (ch.added) said.push(plural(ch.added, 'move added', 'moves added'));
  if (ch.cut) said.push(plural(ch.cut, 'branch cut', 'branches cut'));
  if (ch.marks) said.push(plural(ch.marks, 'mark changed', 'marks changed'));
  // a line the suggestion adds that ends without a right mark is a wrong answer on the site
  const loose = S.tree.filter(m => !m.gone && !m.ghost && (m.added || m.fresh) && !rightNow(m) && !m.children.some(c => !c.ghost));
  const hints = [];
  if (why) hints.push(why);
  if (loose.length) hints.push(`${loose.map(m => m.move).join(', ')} ${loose.length === 1 ? 'ends a line' : 'end lines'} the suggestion adds without a right mark.`);
  if (S.adding && !why) hints.push('Click an empty point on the board to add a move at the position shown (Esc to stop).');
  $('tree-edit').innerHTML = `
    <button id="ed-right" ${why || !mine || n.ghost ? 'disabled' : ''}
      title="Mark the selected move as the end of a correct line, or take the mark off (moves the suggestion adds)">${mine && rightNow(n) ? 'Unmark right' : 'Mark right'}</button>
    <button id="ed-cut" ${why || !mine ? 'disabled' : ''}
      title="${n.ghost ? 'Bring back the branch cut here' : 'Cut the selected move and everything after it (moves the suggestion adds)'}">${n.ghost ? 'Restore branch' : 'Cut branch'}</button>
    <button id="ed-add" class="${S.adding ? 'on' : ''}" aria-pressed="${S.adding}" ${why ? 'disabled' : ''}
      title="Add moves: click the board to play a move at the position shown, which goes into the suggestion">Add moves</button>
    <span class="ed-state">${said.length ? 'Not saved: ' + said.join(', ') : ''}</span>
    <button id="ed-save" class="save" ${ch.total && !why ? '' : 'disabled'} title="Save the edits: the suggestion is shown and sent as edited">Save</button>
    <button id="ed-discard" ${ch.total ? '' : 'disabled'} title="Drop the edits not saved">Discard</button>
    ${S.item.edited && !ch.total && !why ? '<button id="ed-revert" title="Go back to the lines as the checker made them">Undo all edits</button>' : ''}
    <p id="ed-msg" class="ed-msg">${esc(hints.join(' '))}</p>`;
  $('ed-right').onclick = markRight;
  $('ed-cut').onclick = cutBranch;
  $('ed-add').onclick = () => { S.adding = !S.adding; renderTree(); };
  $('ed-save').onclick = saveEdits;
  $('ed-discard').onclick = discardEdits;
  if ($('ed-revert')) $('ed-revert').onclick = revertEdits;
}

function say(msg) {
  $('ed-msg').textContent = msg;
}

function markRight() {
  const n = S.sel;
  if (!(n.added || n.fresh) || n.ghost || locked()) return;
  n.pendRight = !rightNow(n);
  if (n.pendRight === n.right) delete n.pendRight;
  renderTree();
}

// cuts the selected branch, or brings it back when it is cut: a branch saved stays as a
// ghost until the edits are saved, one added since goes at once
function cutBranch() {
  const n = S.sel;
  if (locked()) return;
  if (n.ghost) {
    let top = n;
    while (!top.cut) top = top.parent;
    delete top.cut;
    return renderTree();
  }
  if (!(n.added || n.fresh)) return;
  if (n.fresh) {
    const gone = m => { m.gone = true; m.children.forEach(gone); };
    gone(n);
    n.parent.children.splice(n.parent.children.indexOf(n), 1);
    return selectNode(n.parent);
  }
  n.cut = true;
  renderTree();
}

// the side to play at the node: the other side from its move, at the start the solver
function toMove(n) {
  if (n.move) return n.move[0] === 'B' ? 'W' : 'B';
  return S.tree[0].children.find(c => c.move)?.move[0] ?? 'B';
}

// a move added by hand at the selected node, by the side to play there: a branch of the
// suggestion, not saved yet, selected so the next can be added after it
function addMove(x, y) {
  const n = S.sel;
  if (locked()) return;
  if (n.ghost) return say('A branch cut by an edit takes no moves: restore it first.');
  const color = toMove(n), move = `${color} ${S.item.columns[x]}${n.rows.length - y}`;
  const there = n.children.find(c => c.move === move);
  if (there) return selectNode(there);
  const ko = n.ko !== undefined ? n.ko : n.parent && n.x != null ? play(n.parent.rows, n.x, n.y, n.move[0])?.ko : null;
  if (ko && ko[0] === x && ko[1] === y) return say(`${move.slice(2)} would take back the ko at once.`);
  const after = play(n.rows, x, y, color);
  if (!after) return say(`${move.slice(2)} cannot be played there.`);
  const c = {i: S.tree.length, parent: n, children: [], depth: n.depth + 1, move, x, y, rows: after.rows, ko: after.ko,
    added: false, right: false, fresh: true};
  S.tree.push(c);
  n.children.push(c);
  selectNode(c);
}

async function saveEdits() {
  // every node the suggestion is to add, parents first, as the tree's order has them
  const added = S.tree.filter(n => !n.gone && !n.ghost && (n.added || n.fresh) && n.x != null)
    .map(n => ({at: sgfPath(n.parent), move: sgfOf(n), right: rightNow(n)}));
  await sendEdits({base: S.item.base, added});
}

function discardEdits() {
  const path = pathOf(S.sel);
  S.tree = buildTree(S.item.tree);
  S.choice = new Map();
  selectNode(deepest(path));
}

async function revertEdits() {
  if (!confirm(`Undo all the edits to ${S.name}'s tree, back to the lines the checker made?`)) return;
  await sendEdits({base: S.item.base, revert: true});
}

// the edits to the server, then the suggestion read again as it now stands, keeping the place
// in the tree and what was typed in the comment and note
async function sendEdits(body) {
  try {
    await api('/api/edit?name=' + encodeURIComponent(S.name), {method: 'POST', body: JSON.stringify(body)});
  } catch (e) {
    return say(e.message);
  }
  const path = pathOf(S.sel), comment = $('comment').value, note = $('note').value, top = $('item').scrollTop;
  const typed = comment !== (S.item.approval?.comment || S.item.draft.comment);
  S.item = await api('/api/item?name=' + encodeURIComponent(S.name));
  renderItem(path);
  if (typed) $('comment').value = comment;
  $('note').value = note;
  $('item').scrollTop = top;
  loadList();
}

async function decide(decision) {
  if (unsaved()) {
    $('status').textContent = 'Save or discard the edits to the tree first.';
    return;
  }
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
  else if (e.key === 'Escape' && S.adding) {
    S.adding = false;
    renderTree();
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    const f = $('item').querySelector('figure.on');
    if (!f) return;
    e.preventDefault();
    f.querySelectorAll('button')[e.key === 'ArrowLeft' ? 0 : 1].click();
  } else if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && $('tree-board')?.classList.contains('on')) {
    e.preventDefault();
    pickBranch(e.key === 'ArrowUp' ? -1 : 1);
  }
});

// edits not saved are lost with the page
window.addEventListener('beforeunload', e => {
  if (!unsaved()) return;
  e.preventDefault();
  e.returnValue = '';
});

window.onhashchange = () => select(location.hash.slice(1));
loadList().then(() => {
  if (location.hash) select(location.hash.slice(1));
  else if (visible().length) location.hash = visible()[0].name;
});
