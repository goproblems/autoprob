'use strict';

// review page for a redo run: browse each problem's generated solution tree next to the
// author's, see the candidates behind every decision, and judge them. judgments are
// saved straight to the case library by the server.
//
// a judgment is about the selected move: whether it belongs in the tree at the position
// it was played from. it is stored in the case for that earlier position, so the table
// and the numbered board markers show the choices that were available there.

const TAG_HELP = {
  'ended-early': 'The line stopped while the opponent still had a real try',
  'ended-late': 'The line kept going after the problem was settled',
  'wrong-resistance': 'This response is not the one that belongs in the main line',
  'missing-move': 'A move that works, or a resistance that matters, is missing from the tree',
  'unnatural-move': 'A move no human would consider',
  'missed-tesuji': 'The solver missed the key move',
  'wrong-solver-move': 'This solver move is wrong or inferior',
  'isolation-broken': 'The isolated position misrepresents the problem (stakes, komi, fortress)',
};
const TAGS = Object.keys(TAG_HELP);
const COLS = 'ABCDEFGHJKLMNOPQRST';
const END = 'END';
const LOW_PRIOR = 0.02; // below this katago prior a candidate is drawn dotted unless the tree plays it

// tree colors: goproblems green where the trees agree, light green where only the
// author has a move (on the board too)
const BOTH = '#65a30d', GEN = '#3b82f6', AUTHOR = '#a3e635';
const RIGHT_MARK = '#22c55e', WRONG_MARK = '#ef4444';

const state = {
  run: null,
  name: null,
  data: null,
  tree: null, // merged tree of the generated and author correct lines
  sel: 'root', // selected position: moves from the root, or "root"
  choice: {},  // per position, the index of the child the right arrow goes to
  full: false,
  stakes: false,
};

const $ = (id) => document.getElementById(id);
const keyOf = (at) => (at.length ? at.join(' ') : 'root');
const pt = (move) => move.slice(2);
const lbl = (m) => (m === END ? 'end' : pt(m));
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
const caseAt = (key) => (caseFile().decisions || []).find((c) => keyOf(c.at) === key);
const decisionAt = (key) => record().decisions.find((d) => keyOf(d.at) === key);
const genLines = () => (record().generatedLines && record().generatedLines.length
  ? record().generatedLines : (record().generated.length ? [record().generated] : []));
const chosenMoves = (d) => [d.chose, ...(d.alternatives || [])];
const isSolverMove = (move) => move[0] === record().isolation.solver;

// the position the selected move was played from: what a judgment is about. at the start
// position, the choice of first move
function judgedKey() {
  const node = state.tree.index[state.sel];
  return node.parent ? node.parent.key : 'root';
}

// right / wrong / null for a move at a position, from the case there
function verdictOf(key, move) {
  const c = caseAt(key);
  if (!c) return null;
  return c.expect.choose.includes(move) ? 'right' : c.expect.avoid.includes(move) ? 'wrong' : null;
}

// mirrors DecisionCase.passes: no chosen move may be avoided; a solver turn needs every
// right move in the tree, an opponent turn one of the acceptable resistances
function passes(c, d) {
  const moves = chosenMoves(d);
  if (moves.some((m) => c.expect.avoid.includes(m))) return false;
  if (d.role === 'solver') return c.expect.choose.every((m) => moves.includes(m));
  return c.expect.choose.length === 0 || moves.some((m) => c.expect.choose.includes(m));
}

// the generated and author correct trees merged into one. each node is a position:
// gen / author say which tree has the move leading to it, genEnd / authorEnd which
// tree ends a line there
function buildTree() {
  const root = { key: 'root', at: [], move: null, children: [], depth: 0, gen: true, author: true };
  const index = { root };
  const add = (line, flag) => {
    let node = root;
    line.forEach((m, i) => {
      const at = line.slice(0, i + 1);
      const key = keyOf(at);
      let child = index[key];
      if (!child) {
        child = { key, at, move: m, children: [], depth: i + 1, parent: node };
        index[key] = child;
        node.children.push(child);
      }
      child[flag] = true;
      node = child;
    });
    node[flag + 'End'] = true;
  };
  genLines().forEach((l) => add(l, 'gen'));
  record().refs.forEach((l) => add(l, 'author'));
  const order = [];
  const walk = (n) => { order.push(n); n.children.forEach(walk); };
  walk(root);
  return { root, index, order };
}

// what the author's RIGHT lines do at a position
function authorMoves(key) {
  const node = state.tree.index[key];
  if (!node) return [];
  const out = node.children.filter((c) => c.author).map((c) => c.move);
  if (node.authorEnd) out.push(END);
  return out;
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
  else $('empty').textContent = state.run.manifest.finished ? 'This run has no problems.'
    : 'No problems have finished yet. They appear here as they do.';
  if (!state.run.manifest.finished) state.poll = setInterval(refreshRun, 30000);
}

// while a run is still going, pick up problems as they finish
async function refreshRun() {
  try {
    state.run = await api('/api/run');
  } catch (e) {
    return; // the server may be restarting; try again next time
  }
  renderTop();
  renderProblems();
  if (!state.name && state.run.problems.length) await openProblem(state.run.problems[0].problem);
  if (state.run.manifest.finished) clearInterval(state.poll);
}

async function openProblem(name) {
  state.name = name;
  history.replaceState(null, '', '#' + encodeURIComponent(name));
  state.data = await api('/api/problem?name=' + encodeURIComponent(name));
  const ok = !!record().isolation;
  $('empty').hidden = ok;
  $('boardPane').hidden = !ok;
  $('side').hidden = !ok;
  if (!ok) {
    $('empty').textContent = name + ' failed in this run: ' + (record().error || 'no isolation recorded');
    renderProblems();
    return;
  }
  state.tree = buildTree();
  state.sel = 'root';
  state.choice = {};
  renderProblems();
  render();
}

// ---------- rendering ----------

function renderTop() {
  const m = state.run.manifest;
  const s = m.summary;
  const model = (m.model || '').split(/[\\/]/).pop();
  const parts = ['run ' + m.id, 'commit ' + m.commit, model];
  if (s) parts.push(`first move ${s.firstMoveMatch}/${s.evaluated} · exact ${s.exact}/${s.evaluated}`);
  else parts.push(`not finished: ${m.problems.length} problem${m.problems.length === 1 ? '' : 's'} so far`);
  parts.push('reviewing as ' + state.run.by);
  $('runInfo').textContent = parts.join('  ·  ');
}

function renderProblems() {
  const nav = $('problems');
  nav.innerHTML = '';
  for (const p of state.run.problems) {
    const b = document.createElement('button');
    b.className = 'prob' + (p.problem === state.name ? ' sel' : '');
    b.title = `Open ${p.problem} (p / n for the previous / next problem)`;
    const m = p.metrics, t = p.tree;
    const badges = [];
    if (!p.error) {
      badges.push(`<span class="badge ${p.judged === p.decisions ? 'good' : ''}" title="choices in the generated tree you have judged, of all its choices">${p.judged}/${p.decisions}</span>`);
      if (p.failing) badges.push(`<span class="badge bad" title="choices where this run goes against your judgments">${p.failing} wrong</span>`);
      if (t && t.missingSolverMoves.length) badges.push(`<span class="badge bad" title="author's working moves missing from the tree">${t.missingSolverMoves.length} missing</span>`);
      if (p.accepted) badges.push('<span class="badge good" title="you accepted the whole generated tree">accepted</span>');
    }
    let meta = '';
    if (p.error) meta = 'error: ' + esc(p.error);
    else if (t) meta = `${t.generatedLines} lines vs author ${t.authorLines} · ${t.reproduced} reproduced`;
    else if (m) meta = `prefix ${m.prefix} · ${m.genLen} vs ${m.refLen} moves${m.exact ? ' · exact' : ''}`;
    b.innerHTML = `<div class="name">${esc(p.problem)} ${badges.join(' ')}</div><div class="meta">${meta}</div>`;
    b.onclick = () => openProblem(p.problem);
    nav.appendChild(b);
  }
}

function render() {
  renderBoard();
  renderTree();
  renderSide();
}

function select(key) {
  const node = state.tree.index[key];
  if (!node) return;
  state.sel = key;
  // keep the current line running through the selected position
  for (let n = node; n.parent; n = n.parent) state.choice[n.parent.key] = n.parent.children.indexOf(n);
  render();
  const el = document.querySelector(`#tree [data-key="${CSS.escape(key)}"]`);
  if (el) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

// the child the right arrow goes to: the one chosen with up / down, else the first
function chosenChild(node) {
  if (!node || !node.children.length) return null;
  return node.children[Math.min(state.choice[node.key] ?? 0, node.children.length - 1)];
}

// choose the next or previous child of the selected position
function chooseChild(step) {
  const node = state.tree.index[state.sel];
  if (!node || node.children.length < 2) return;
  const i = Math.min(state.choice[node.key] ?? 0, node.children.length - 1);
  state.choice[node.key] = Math.max(0, Math.min(node.children.length - 1, i + step));
  renderTree();
  const next = chosenChild(node);
  const el = document.querySelector(`#tree [data-key="${CSS.escape(next.key)}"]`);
  if (el) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

function renderBoard() {
  const boards = state.data.boards;
  const node = state.tree.index[state.sel];
  const rows = boards[state.sel];
  const iso = record().isolation;
  // the choice the selected move was played from
  const jk = judgedKey();
  const d = decisionAt(jk);
  const c = caseAt(jk);

  // crop to the problem: every real stone at any position of either tree. this stays
  // fixed for the whole problem so the board does not resize between positions;
  // candidates outside it are too far from the problem to matter and are only listed
  // in the table
  let x0 = 18, y0 = 18, x1 = 0, y1 = 0;
  const grow = (x, y) => { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); };
  for (const b of Object.values(boards)) {
    b.forEach((row, y) => [...row].forEach((ch, x) => { if (ch === 'X' || ch === 'O') grow(x, y); }));
  }
  if (state.full || x1 < x0) { x0 = 0; y0 = 0; x1 = 18; y1 = 18; } else {
    x0 = Math.max(0, x0 - 2); y0 = Math.max(0, y0 - 2); x1 = Math.min(18, x1 + 2); y1 = Math.min(18, y1 + 2);
  }

  // the coordinates get their own strip on the top and left, and the outermost points sit
  // more than a stone's radius inside the board, so stones never cover the labels
  const cell = 34, label = 16, inset = cell * 0.6;
  const w = (x1 - x0) * cell + label + 2 * inset, h = (y1 - y0) * cell + label + 2 * inset;
  const X = (x) => label + inset + (x - x0) * cell, Y = (y) => label + inset + (y - y0) * cell;
  const inView = (x, y) => x >= x0 && x <= x1 && y >= y0 && y <= y1;
  const parts = [];
  parts.push(`<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="board">`);
  parts.push(`<rect width="${w}" height="${h}" rx="6" fill="var(--wood)"/>`);
  // where the crop cuts the board, lines run a little past the edge to show it goes on,
  // stopping short of the coordinate labels
  const ext = cell * 0.25;
  for (let x = x0; x <= x1; x++) {
    const top = Y(y0) - (y0 > 0 ? ext : 0), bottom = Y(y1) + (y1 < 18 ? ext : 0);
    parts.push(`<line x1="${X(x)}" y1="${top}" x2="${X(x)}" y2="${bottom}" stroke="var(--grid)" stroke-width="1"/>`);
    parts.push(`<text x="${X(x)}" y="${label * 0.8}" font-size="11" text-anchor="middle" fill="var(--grid)">${COLS[x]}</text>`);
  }
  for (let y = y0; y <= y1; y++) {
    const left = X(x0) - (x0 > 0 ? ext : 0), right = X(x1) + (x1 < 18 ? ext : 0);
    parts.push(`<line x1="${left}" y1="${Y(y)}" x2="${right}" y2="${Y(y)}" stroke="var(--grid)" stroke-width="1"/>`);
    parts.push(`<text x="${label * 0.6}" y="${Y(y) + 4}" font-size="11" text-anchor="middle" fill="var(--grid)">${19 - y}</text>`);
  }
  for (const sx of [3, 9, 15]) for (const sy of [3, 9, 15]) {
    if (inView(sx, sy)) parts.push(`<circle cx="${X(sx)}" cy="${Y(sy)}" r="2.6" fill="var(--grid)"/>`);
  }

  // the selected move's marker takes the color of your verdict on it
  const lastXY = node && node.move ? gtpXY(pt(node.move)) : null;
  const verdict = node && node.move ? verdictOf(jk, node.move) : null;
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
        const color = verdict === 'right' ? RIGHT_MARK : verdict === 'wrong' ? WRONG_MARK : mark;
        parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.18}" fill="none" stroke="${color}" stroke-width="${verdict ? 3 : 2}"/>`);
      } else if (state.stakes && stakes.has(COLS[x] + (19 - y))) {
        const r = cell * 0.2;
        parts.push(`<polygon points="${X(x)},${Y(y) - r} ${X(x) - r},${Y(y) + r * 0.75} ${X(x) + r},${Y(y) + r * 0.75}" fill="none" stroke="${mark}" stroke-width="1.6"/>`);
      }
    }
  }

  // the other choices at the position the selected move was played from, numbered as in
  // the table; moves the tree plays get a thick ring, judged moves are colored
  const played = d ? chosenMoves(d) : [];
  if (d) {
    const expect = c ? c.expect : { choose: [], avoid: [] };
    const labeled = new Set();
    d.candidates.forEach((cand, i) => {
      if (pt(cand.move) === 'pass') return;
      const [x, y] = gtpXY(pt(cand.move));
      if (!inView(x, y) || rows[y][x] !== '.') return;
      labeled.add(cand.move);
      const inTree = played.includes(cand.move);
      const color = expect.choose.includes(cand.move) ? 'var(--good)' : expect.avoid.includes(cand.move) ? 'var(--bad)'
        : inTree ? 'var(--accent)' : '#3a2a12';
      // a katago move it barely considered, and the tree does not play: not interesting
      const dull = !inTree && !expect.choose.includes(cand.move) && cand.source === 'katago' && (cand.prior ?? 0) < LOW_PRIOR;
      parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.36}" fill="var(--wood)" stroke="${color}" stroke-width="${inTree ? 3 : 1.5}"${dull ? ' stroke-dasharray="2 3"' : ''}/>`);
      parts.push(`<text x="${X(x)}" y="${Y(y) + 5}" font-size="14" font-weight="700" text-anchor="middle" fill="${color}">${i + 1}</text>`);
    });
    for (const m of expect.choose) {
      if (m === END || labeled.has(m)) continue;
      const [x, y] = gtpXY(pt(m));
      if (!inView(x, y) || rows[y][x] !== '.') continue;
      parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.36}" fill="none" stroke="var(--good)" stroke-width="3"/>`);
    }
    for (const m of expect.avoid) {
      if (m === END || labeled.has(m)) continue;
      const [x, y] = gtpXY(pt(m));
      if (!inView(x, y) || rows[y][x] !== '.') continue;
      const r = cell * 0.25;
      parts.push(`<path d="M${X(x) - r} ${Y(y) - r}L${X(x) + r} ${Y(y) + r}M${X(x) + r} ${Y(y) - r}L${X(x) - r} ${Y(y) + r}" stroke="var(--bad)" stroke-width="3"/>`);
    }
  }

  // the author's moves at that position that the generated tree does not play: a light
  // green ring, with a star where there is no candidate number inside
  const numbered = new Set(d ? d.candidates.map((cand) => cand.move) : []);
  for (const m of authorMoves(jk)) {
    if (m === END || played.includes(m)) continue;
    const [x, y] = gtpXY(pt(m));
    if (!inView(x, y) || rows[y][x] !== '.') continue;
    parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.46}" fill="none" stroke="#3f6212" stroke-width="5.5" opacity="0.35"/>`);
    parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.46}" fill="none" stroke="${AUTHOR}" stroke-width="3.5"/>`);
    if (!numbered.has(m)) {
      parts.push(`<text x="${X(x)}" y="${Y(y) + 6}" font-size="17" text-anchor="middle" fill="#3f6212">★</text>`);
    }
  }
  parts.push('</svg>');
  $('board').innerHTML = parts.join('');

  const moveNo = node ? node.depth : 0;
  $('stepLabel').textContent = moveNo === 0 ? 'start position' : `after move ${moveNo}: ${lbl(node.move)}`;
  // whose turn it is now: the opposite of the last move, or the solver at the start
  const toMove = node && node.move ? (node.move[0] === 'B' ? 'W' : 'B') : iso.solver;
  const icon = $('toMove');
  icon.className = 'to-move ' + toMove;
  icon.title = colorName(toMove) + ' to move';
  icon.setAttribute('aria-label', icon.title);
  $('showStakes').checked = state.stakes;
  $('fullBoard').checked = state.full;
  $('notes').innerHTML = (record().notes || []).map((n) => `<div>${esc(n)}</div>`).join('');
}

// the merged correct tree, laid out like the goproblems.com tree: depth runs right,
// branches stack downward, a branch drops straight down from its parent then slants in
function renderTree() {
  const { root, order } = state.tree;
  const sky = [];
  const place = (node, row) => {
    node.row = row;
    sky[node.depth] = Math.max(sky[node.depth] ?? -1, row);
    node.children.forEach((c, i) => {
      let r = row;
      if (i > 0) {
        // clear every column this branch's main chain will run through
        let lowest = -1;
        for (let n = c; n; n = n.children[0]) lowest = Math.max(lowest, sky[n.depth] ?? -1);
        r = Math.max(row + 1, lowest + 1);
      }
      place(c, r);
    });
  };
  place(root, 0);

  const DX = 34, DY = 30, R = 9, PAD = 18;
  const X = (n) => PAD + n.depth * DX, Y = (row) => PAD + row * DY;
  const maxDepth = Math.max(...order.map((n) => n.depth));
  const maxRow = Math.max(...order.map((n) => n.row));
  const w = X({ depth: maxDepth }) + PAD, h = Y(maxRow) + PAD;
  const status = (n) => (n.gen && n.author ? BOTH : n.gen ? GEN : AUTHOR);
  const parts = [`<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" role="tree" aria-label="correct tree">`];

  // the current line: from the start to the selected position, then on through the
  // chosen children. it is drawn thick, everything else thin
  const onLine = new Set();
  for (let n = state.tree.index[state.sel]; n; n = n.parent) onLine.add(n.key);
  for (let n = chosenChild(state.tree.index[state.sel]); n; n = chosenChild(n)) onLine.add(n.key);

  // lines first, under the stones; the current line last so it sits on top
  const edges = order.filter((n) => n.parent);
  edges.sort((a, b) => onLine.has(a.key) - onLine.has(b.key));
  for (const n of edges) {
    const color = status(n);
    const thick = onLine.has(n.key);
    const dash = n.gen ? '' : ` stroke-dasharray="${thick ? '6 5' : '4 4'}"`;
    const px = X(n.parent), py = Y(n.parent.row), x = X(n), y = Y(n.row);
    const style = `stroke="${color}" stroke-width="${thick ? 4.6 : 2}" stroke-linecap="round"${dash} fill="none"`;
    if (n.row === n.parent.row) {
      parts.push(`<line x1="${px}" y1="${py}" x2="${x}" y2="${y}" ${style}/>`);
    } else {
      parts.push(`<polyline points="${px},${py} ${px},${Y(n.row - 1)} ${x},${y}" ${style}/>`);
    }
  }

  for (const n of order) {
    const x = X(n), y = Y(n.row);
    const tip = [];
    parts.push(`<g class="tnode" data-key="${esc(n.key)}">`);
    if (n.key === state.sel) {
      parts.push(`<rect x="${x - 13}" y="${y - 13}" width="26" height="26" rx="3" fill="#7DD3FC"/>`);
    }
    if (n.genEnd || n.authorEnd) {
      const endColor = n.genEnd && n.authorEnd ? BOTH : n.genEnd ? GEN : AUTHOR;
      parts.push(`<circle cx="${x}" cy="${y}" r="${R * 1.3}" fill="${endColor}"/>`);
      tip.push(n.genEnd && n.authorEnd ? 'both trees end here' : n.genEnd ? 'only the generated tree ends here'
        : 'only the author ends here');
    }
    if (!n.move) {
      // the start position: a small house, as on the site
      parts.push(`<path d="M${x - 7} ${y + 6}V${y - 1}L${x} ${y - 7}L${x + 7} ${y - 1}V${y + 6}Z" fill="var(--muted)"/>`);
      tip.unshift('start position');
    } else {
      const black = n.move[0] === 'B';
      parts.push(`<circle cx="${x}" cy="${y}" r="${R}" fill="${black ? 'rgb(41,41,41)' : 'rgb(242,242,242)'}"
        stroke="${black ? '#000' : 'rgb(131,131,131)'}" stroke-width="0.8" opacity="${n.gen ? 1 : 0.55}"/>`);
      tip.unshift(`${n.depth}. ${pt(n.move)}` + (n.gen && n.author ? '' : n.gen ? ' (generated only)' : ' (author only)'));
      // your verdict on this move: a green dot for right, red for wrong
      const v = verdictOf(n.parent.key, n.move);
      if (v) {
        parts.push(`<circle cx="${x}" cy="${y}" r="3.4" fill="${v === 'right' ? RIGHT_MARK : WRONG_MARK}" stroke="${black ? '#fff' : '#000'}" stroke-width="0.6"/>`);
        tip.push(v === 'right' ? 'marked right' : 'marked wrong');
      }
    }
    // whether the line should end after this move
    const ending = verdictOf(n.key, END);
    if (ending) tip.push(ending === 'right' ? 'marked: the line ends here' : 'marked: the line should continue');
    parts.push(`<title>${esc(tip.join(' · '))}</title>`);
    parts.push(`<circle cx="${x}" cy="${y}" r="${R + 4}" fill="transparent"/>`);
    parts.push('</g>');
  }
  parts.push('</svg>');
  const holder = $('tree');
  holder.innerHTML = parts.join('');
  holder.querySelectorAll('.tnode').forEach((g) => { g.onclick = () => select(g.dataset.key); });
}

function renderSide() {
  const rec = record();
  const t = rec.tree;
  const lines = genLines();
  const accepted = lines.length > 0 && lines.every((l) =>
    (caseFile().acceptedLines || []).some((a) => a.join(' ') === l.join(' ')));
  let head = `<div class="head-row"><span><b>Correct tree</b> · generated ${lines.length} line${lines.length === 1 ? '' : 's'}, author ${rec.refs.length}`;
  if (t) head += `, ${t.reproduced} reproduced`;
  if (accepted) head += ' · <span class="badge good">accepted</span>';
  head += `</span><button data-act="accept" title="${accepted
    ? 'Stop treating the generated lines as acceptable (A)'
    : 'Mark every choice in the generated tree that you have not judged as right as generated, and keep its lines as acceptable (A)'}">${accepted ? 'Unaccept tree' : 'Accept tree'} <kbd>A</kbd></button></div>`;
  if (t) {
    const gaps = [];
    if (t.missingSolverMoves.length) gaps.push(`<span class="bad-text">author's working moves missing: ${t.missingSolverMoves.map(esc).join('; ')}</span>`);
    if (t.extraSolverMoves.length) gaps.push(`extra working moves: ${t.extraSolverMoves.map(esc).join('; ')}`);
    if (t.missingResponses.length) gaps.push(`author's resistances missing: ${t.missingResponses.map(esc).join('; ')}`);
    if (t.extraResponses.length) gaps.push(`extra resistances: ${t.extraResponses.map(esc).join('; ')}`);
    if (t.endsEarly.length) gaps.push(`ends before the author at: ${t.endsEarly.map(esc).join('; ')}`);
    if (t.endsLate.length) gaps.push(`continues past the author's end at: ${t.endsLate.map(esc).join('; ')}`);
    if (gaps.length) head += `<ul class="gaps">${gaps.map((g) => `<li>${g}</li>`).join('')}</ul>`;
  }
  $('lineHead').innerHTML = head;
  $('lineHead').querySelector('[data-act="accept"]').onclick = () => acceptTree();
  renderDecision();
}

function renderDecision() {
  const el = $('decision');
  const node = state.tree.index[state.sel];
  const move = node.move;
  const iso = record().isolation;
  const jk = judgedKey();
  const d = decisionAt(jk);
  const c = caseAt(jk);
  const expect = c ? c.expect : { choose: [], avoid: [] };
  const parts = [];

  if (move) {
    // the selected move and your verdict on it
    const where = node.gen && node.author ? 'in both trees' : node.gen ? 'generated tree only' : 'author only';
    const solverMove = isSolverMove(move);
    const v = verdictOf(jk, move);
    const rightMeans = solverMove ? 'it works, so it must be in the tree' : 'it is a good resistance for the tree to play';
    parts.push(`<h3>${node.depth}. ${colorName(move[0])} ${esc(pt(move))} <span class="badge">${where}</span></h3>`);
    parts.push(`<div class="verdict ${v || 'none'}">${v === 'right' ? 'You marked this move right'
      : v === 'wrong' ? 'You marked this move wrong' : 'This move is not judged yet'}</div>`);
    parts.push(`<div class="actions">
      <button class="good ${v === 'right' ? 'on' : ''}" data-act="right" title="Mark this move right: ${rightMeans} (g)">Right <kbd>g</kbd></button>
      <button class="bad ${v === 'wrong' ? 'on' : ''}" data-act="wrong" title="Mark this move wrong: it must not be in the tree (b)">Wrong <kbd>b</kbd></button>
      <button data-act="clear" title="Remove your verdict on this move (x)">Clear <kbd>x</kbd></button>
    </div>`);

    // tags and note for this move
    const n = (c && c.moves && c.moves[move]) || { tags: [], note: '' };
    const tags = [...new Set([...TAGS, ...(n.tags || [])])].map((tg) =>
      `<button class="tag ${(n.tags || []).includes(tg) ? 'on' : ''}" data-tag="${esc(tg)}" title="${esc(TAG_HELP[tg] || 'your own tag')}: click to add or remove it on this move">${esc(tg)}</button>`).join('');
    parts.push(`<div class="section-label">Tags on this move</div>
      <div class="tags">${tags}<input id="tagInput" placeholder="new tag…" aria-label="new tag" title="Type a new tag and press Enter to add it to this move"></div>
      <div class="section-label">Note on this move</div>
      <textarea id="noteInput" placeholder="why, in a sentence" title="Saved when you leave the box">${esc(n.note || '')}</textarea>`);

    // after a solver move: should the line end here?
    if (solverMove) {
      const dn = decisionAt(node.key);
      const ending = verdictOf(node.key, END);
      const gen = dn ? (dn.chose === END ? 'The generated line ends here.'
        : `The generated tree goes on: ${colorName(dn.toMove)} answers ${chosenMoves(dn).map(pt).join(', ')}.`)
        : node.gen ? 'The generated line was cut short here.' : 'The generator never reached this position.';
      const authorNext = node.children.filter((ch) => ch.author).map((ch) => pt(ch.move));
      const auth = node.authorEnd ? ' The author ends the line here.'
        : authorNext.length ? ` The author goes on with ${authorNext.join(', ')}.` : '';
      parts.push(`<div class="section-label">After this move</div>
        <div class="reason">${esc(gen + auth)}</div>
        <div class="actions">
          <button class="good ${ending === 'right' ? 'on' : ''}" data-act="end" title="The line should end after this move: the opponent has no real try left. Any response the tree plays here is marked wrong (e)">Line ends here <kbd>e</kbd></button>
          <button class="bad ${ending === 'wrong' ? 'on' : ''}" data-act="continue" title="The line should not end after this move: the opponent still has a real try (c)">Line continues <kbd>c</kbd></button>
        </div>`);
    }
  } else {
    parts.push(`<h3>Start position · ${colorName(iso.solver)} to play</h3>
      <div class="reason">Select a move in the tree to judge it.</div>`);
  }

  // the choices at the position the selected move was played from
  const author = authorMoves(jk);
  const played = d ? chosenMoves(d) : [];
  const missing = author.filter((m) => !played.includes(m));
  const authorText = (author.length ? 'Author: ' + author.map(lbl).join(', ') : 'Off the author\'s correct tree')
    + (d && missing.length ? ` (not in the generated tree: ${missing.map(lbl).join(', ')})` : '');
  parts.push(`<div class="section-label">${move ? `Choices where ${esc(pt(move))} was played` : 'First move choices'}</div>`);
  if (!d) {
    parts.push(`<div class="reason">The generator never reached this position, so there are no candidates to show.<br>${esc(authorText)}</div>`);
  } else {
    const solver = d.role === 'solver';
    const choseText = d.chose === END ? 'end the line' : (solver ? 'play ' : 'answer ') + played.map(pt).join(', ');
    parts.push(`<div class="reason">The generator chose to ${esc(choseText)}: ${esc(d.reason || '')}<br>${esc(authorText)}</div>`);
    if (c) {
      const ok = passes(c, d);
      parts.push(`<div class="status ${ok ? 'pass' : 'fail'}" title="${solver
        ? 'At a solver turn every move you marked right must be in the tree, and none you marked wrong'
        : 'At an opponent turn the tree must play one of the moves you marked right, and none you marked wrong'}">${ok
        ? 'The generator\'s choice here agrees with your judgments' : 'The generator\'s choice here goes against your judgments'}
        <span class="muted">(${esc(c.source)}${c.by && c.by !== c.source ? ', ' + esc(c.by) : ''})</span></div>`);
    }
    const star = (m) => (author.includes(m) ? ' <span class="muted" title="the author plays this">★</span>' : '');
    const headRow = solver
      ? '<th></th><th class="l">#</th><th class="l">move</th><th class="l">from</th><th>prior</th><th>visits</th><th>human</th><th title="the solver\'s score lead after this move">lead after</th><th>in tree</th>'
      : '<th></th><th class="l">#</th><th class="l">move</th><th class="l">from</th><th>prior</th><th>visits</th><th>human</th><th title="stones that change fate if this move is ignored">threat</th><th class="l">answer</th><th>ans prior</th><th>human ans</th><th title="solver answers that hold the result">answers</th><th title="distance to the stones at stake">stake</th>';
    const rows = d.candidates.map((cand, i) => {
      const mark = expect.choose.includes(cand.move) ? '<td class="mark good">✓</td>'
        : expect.avoid.includes(cand.move) ? '<td class="mark bad">✗</td>' : '<td class="mark"></td>';
      const cls = [played.includes(cand.move) ? 'chosen' : '', cand.move === move ? 'this' : ''].join(' ');
      const cells = solver
        ? `<td class="l">${esc(pt(cand.move))}${star(cand.move)}${cand.move === move ? ' <span class="muted">(this move)</span>' : ''}</td><td class="l dim">${esc(cand.source)}</td>
           <td>${fmt(cand.prior)}</td><td>${fmt(cand.visits)}</td><td>${fmt(cand.humanPolicy)}</td>
           <td>${cand.error ? 'err' : cand.afterLead === undefined || cand.afterLead === null ? '<span class="dim">–</span>' : fmt(cand.afterLead, 1)}</td>
           <td>${played.includes(cand.move) ? (cand.move === d.chose ? 'main' : 'yes') : ''}</td>`
        : `<td class="l">${esc(pt(cand.move))}${star(cand.move)}${cand.move === move ? ' <span class="muted">(this move)</span>' : ''}</td>
           <td class="l dim">${esc(cand.source)}</td><td>${fmt(cand.prior)}</td><td>${fmt(cand.visits)}</td>
           <td>${fmt(cand.humanPolicy)}</td><td>${cand.error ? 'err' : fmt(cand.threat)}</td><td class="l">${esc(cand.answer || '')}</td>
           <td>${fmt(cand.answerPrior)}</td><td>${fmt(cand.humanAnswer)}</td><td>${fmt(cand.answers)}</td><td>${fmt(cand.stakeDist)}</td>`;
      return `<tr class="${cls}">${mark}<td class="l dim">${i + 1}</td>${cells}</tr>`;
    });
    if (!solver) {
      const mark = expect.choose.includes(END) ? '<td class="mark good">✓</td>'
        : expect.avoid.includes(END) ? '<td class="mark bad">✗</td>' : '<td class="mark"></td>';
      rows.push(`<tr class="${d.chose === END ? 'chosen' : ''}">${mark}<td class="l dim"></td>
        <td class="l" colspan="12">end the line${star(END)}</td></tr>`);
    }
    parts.push(`<table class="cands"><thead><tr>${headRow}</tr></thead><tbody>${rows.join('')}</tbody></table>`);
    parts.push(`<div class="muted" style="font-size:12px">${solver
      ? 'Every move that works must be in the tree.'
      : 'The tree should play a resistance you marked right.'} ✓ / ✗ show your verdicts, ★ the author's moves. Select a move in the tree to judge it.</div>`);
  }
  el.innerHTML = parts.join('');

  el.querySelectorAll('[data-act]').forEach((b) => { b.onclick = () => act(b.dataset.act); });
  el.querySelectorAll('[data-tag]').forEach((b) => { b.onclick = () => toggleTag(b.dataset.tag); });
  const tagInput = $('tagInput');
  if (tagInput) {
    tagInput.onkeydown = (ev) => {
      if (ev.key === 'Enter' && tagInput.value.trim()) toggleTag(tagInput.value.trim().toLowerCase().replace(/\s+/g, '-'));
      ev.stopPropagation();
    };
  }
  const note = $('noteInput');
  if (note) {
    note.onchange = () => editMoveNote((n) => { n.note = note.value; });
    note.onkeydown = (ev) => { ev.stopPropagation(); if (ev.key === 'Escape') note.blur(); };
  }
}

// ---------- actions ----------

const without = (list, m) => list.filter((x) => x !== m);

// change the case at a position. mutate edits { expect, tags, note, moves }; a case left
// with nothing in it is deleted
async function editCase(key, mutate) {
  const node = state.tree.index[key];
  const c = caseAt(key);
  const e = {
    expect: c ? { choose: [...c.expect.choose], avoid: [...c.expect.avoid] } : { choose: [], avoid: [] },
    tags: c ? [...(c.tags || [])] : [],
    note: c ? c.note || '' : '',
    moves: c && c.moves ? JSON.parse(JSON.stringify(c.moves)) : {},
  };
  mutate(e);
  const notesEmpty = Object.values(e.moves).every((n) => !(n.tags && n.tags.length) && !(n.note && n.note.trim()));
  const empty = !e.expect.choose.length && !e.expect.avoid.length && !e.tags.length && !e.note.trim() && notesEmpty;
  try {
    if (empty) {
      if (!c) return render();
      state.data.cases = await api(`/api/case-delete?name=${encodeURIComponent(state.name)}&key=${encodeURIComponent(key)}`, { method: 'POST' });
      return afterSave('cleared');
    }
    state.data.cases = await api('/api/case?name=' + encodeURIComponent(state.name), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ at: node.at, expect: e.expect, tags: e.tags, note: e.note, moves: e.moves }),
    });
    afterSave('saved');
  } catch (err) {
    toast('save failed: ' + err.message);
  }
}

// mark a move right or wrong at a position, or clear it (verdict null)
function setVerdict(key, move, verdict) {
  return editCase(key, (e) => {
    e.expect.choose = without(e.expect.choose, move);
    e.expect.avoid = without(e.expect.avoid, move);
    if (verdict === 'right') e.expect.choose.push(move);
    if (verdict === 'wrong') e.expect.avoid.push(move);
  });
}

// right / wrong / clear the selected move; pressing right on a right move clears it
function judgeSelected(verdict) {
  const node = state.tree.index[state.sel];
  if (!node.move) return toast('select a move in the tree to judge it');
  const current = verdictOf(node.parent.key, node.move);
  return setVerdict(node.parent.key, node.move, verdict && current === verdict ? null : verdict);
}

// whether the line should end right after the selected solver move
function judgeEnd(kind) {
  const node = state.tree.index[state.sel];
  if (!node.move || !isSolverMove(node.move)) return toast('select a solver move to judge whether the line ends after it');
  const current = verdictOf(node.key, END);
  const target = kind === 'end' ? 'right' : 'wrong';
  const dn = decisionAt(node.key);
  return editCase(node.key, (e) => {
    e.expect.choose = without(e.expect.choose, END);
    e.expect.avoid = without(e.expect.avoid, END);
    if (current === target) return; // pressed again: clear
    if (kind === 'end') {
      e.expect.choose.push(END);
      // ending here makes any response the tree plays here wrong
      if (dn) {
        for (const m of chosenMoves(dn)) {
          if (m === END) continue;
          e.expect.choose = without(e.expect.choose, m);
          if (!e.expect.avoid.includes(m)) e.expect.avoid.push(m);
        }
      }
    } else {
      e.expect.avoid.push(END);
    }
  });
}

// edit the tags and note on the selected move
function editMoveNote(mutate) {
  const node = state.tree.index[state.sel];
  if (!node.move) return;
  return editCase(node.parent.key, (e) => {
    const n = e.moves[node.move] || { tags: [], note: '' };
    n.tags = n.tags || [];
    mutate(n);
    e.moves[node.move] = n;
  });
}

function toggleTag(tag) {
  return editMoveNote((n) => { n.tags = n.tags.includes(tag) ? without(n.tags, tag) : [...n.tags, tag]; });
}

function act(kind) {
  if (kind === 'right') return judgeSelected('right');
  if (kind === 'wrong') return judgeSelected('wrong');
  if (kind === 'clear') return judgeSelected(null);
  if (kind === 'end') return judgeEnd('end');
  if (kind === 'continue') return judgeEnd('continue');
  if (kind === 'accept') return acceptTree();
}

async function acceptTree() {
  const lines = genLines();
  const accepted = lines.length > 0 && lines.every((l) =>
    (caseFile().acceptedLines || []).some((a) => a.join(' ') === l.join(' ')));
  const path = accepted ? '/api/unaccept-line' : '/api/accept-line';
  state.data.cases = await api(path + '?name=' + encodeURIComponent(state.name), { method: 'POST' });
  afterSave(accepted ? 'tree no longer accepted' : 'tree accepted');
}

function afterSave(msg) {
  // refresh this problem's counts in the list
  const p = state.run.problems.find((x) => x.problem === state.name);
  if (p) {
    let judged = 0, failing = 0;
    for (const d of record().decisions) {
      const c = caseAt(keyOf(d.at));
      if (!c) continue;
      judged++;
      if (!passes(c, d)) failing++;
    }
    p.judged = judged;
    p.failing = failing;
    const lines = genLines();
    p.accepted = lines.length > 0 && lines.every((l) =>
      (caseFile().acceptedLines || []).some((a) => a.join(' ') === l.join(' ')));
  }
  renderProblems();
  render();
  toast(msg);
}

// ---------- keyboard ----------

document.addEventListener('keydown', (ev) => {
  if (ev.target.matches && ev.target.matches('input, textarea')) return;
  if (ev.metaKey || ev.ctrlKey || ev.altKey) return;
  if (!state.data || !record().isolation) return;
  const k = ev.key;
  const problems = state.run.problems;
  const pi = problems.findIndex((p) => p.problem === state.name);
  const node = state.tree.index[state.sel];
  if (k === 'ArrowRight') { const next = chosenChild(node); if (next) select(next.key); }
  else if (k === 'ArrowLeft') { if (node.parent) select(node.parent.key); }
  else if (k === 'ArrowDown') chooseChild(1);
  else if (k === 'ArrowUp') chooseChild(-1);
  else if (k === 'Home') select('root');
  else if (k === 'n' && pi < problems.length - 1) openProblem(problems[pi + 1].problem);
  else if (k === 'p' && pi > 0) openProblem(problems[pi - 1].problem);
  else if (k === 'g') act('right');
  else if (k === 'b') act('wrong');
  else if (k === 'x') act('clear');
  else if (k === 'e') act('end');
  else if (k === 'c') act('continue');
  else if (k === 'A') act('accept');
  else if (k === 's') { state.stakes = !state.stakes; renderBoard(); }
  else if (k === 'f') { state.full = !state.full; renderBoard(); }
  else if (k === '?') $('help').showModal();
  else return;
  ev.preventDefault();
});

$('showStakes').onchange = (ev) => { state.stakes = ev.target.checked; renderBoard(); };
$('fullBoard').onchange = (ev) => { state.full = ev.target.checked; renderBoard(); };
$('helpBtn').onclick = () => $('help').showModal();

init();
