'use strict';

// review page for a redo run: browse each problem's generated solution tree next to the
// author's, see the candidates behind every decision, and judge them. judgments are
// saved straight to the case library by the server.

const TAGS = ['ended-early', 'ended-late', 'wrong-resistance', 'missing-move', 'unnatural-move', 'missed-tesuji',
  'wrong-solver-move', 'isolation-broken'];
const COLS = 'ABCDEFGHJKLMNOPQRST';
const END = 'END';

// tree colors: goproblems green where the trees agree
const BOTH = '#65a30d', GEN = '#2563eb', AUTHOR = '#d97706';

const state = {
  run: null,
  name: null,
  data: null,
  tree: null, // merged tree of the generated and author correct lines
  sel: 'root', // selected position: moves from the root, or "root"
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
  // preorder: the order the arrow keys walk
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
  else $('empty').textContent = 'This run has no problems.';
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
  parts.push('reviewing as ' + state.run.by);
  $('runInfo').textContent = parts.join('  ·  ');
}

function renderProblems() {
  const nav = $('problems');
  nav.innerHTML = '';
  for (const p of state.run.problems) {
    const b = document.createElement('button');
    b.className = 'prob' + (p.problem === state.name ? ' sel' : '');
    const m = p.metrics, t = p.tree;
    const badges = [];
    if (!p.error) {
      badges.push(`<span class="badge ${p.judged === p.decisions ? 'good' : ''}" title="decisions with a case">${p.judged}/${p.decisions}</span>`);
      if (p.failing) badges.push(`<span class="badge bad" title="decisions this run gets wrong">${p.failing} wrong</span>`);
      if (t && t.missingSolverMoves.length) badges.push(`<span class="badge bad" title="author's working moves missing from the tree">${t.missingSolverMoves.length} missing</span>`);
      if (p.accepted) badges.push('<span class="badge good" title="tree accepted">accepted</span>');
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
  if (!state.tree.index[key]) return;
  state.sel = key;
  render();
  const el = document.querySelector(`#tree [data-key="${CSS.escape(key)}"]`);
  if (el) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

function renderBoard() {
  const boards = state.data.boards;
  const node = state.tree.index[state.sel];
  const rows = boards[state.sel];
  const iso = record().isolation;
  const d = decisionAt(state.sel);
  const c = caseAt(state.sel);

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

  const cell = 34, margin = 26;
  const w = (x1 - x0) * cell + 2 * margin, h = (y1 - y0) * cell + 2 * margin;
  const X = (x) => margin + (x - x0) * cell, Y = (y) => margin + (y - y0) * cell;
  const inView = (x, y) => x >= x0 && x <= x1 && y >= y0 && y <= y1;
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
    if (inView(sx, sy)) parts.push(`<circle cx="${X(sx)}" cy="${Y(sy)}" r="2.6" fill="var(--grid)"/>`);
  }

  const lastXY = node && node.move ? gtpXY(pt(node.move)) : null;
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

  // the decision's candidates, numbered as in the table; moves the tree plays get a
  // thick ring, expected moves are colored
  if (d) {
    const expect = c ? c.expect : { choose: [], avoid: [] };
    const chosen = chosenMoves(d);
    const labeled = new Set();
    d.candidates.forEach((cand, i) => {
      if (pt(cand.move) === 'pass') return;
      const [x, y] = gtpXY(pt(cand.move));
      if (!inView(x, y) || rows[y][x] !== '.') return;
      labeled.add(cand.move);
      const inTree = chosen.includes(cand.move);
      const color = expect.choose.includes(cand.move) ? 'var(--good)' : expect.avoid.includes(cand.move) ? 'var(--bad)'
        : inTree ? 'var(--accent)' : '#3a2a12';
      parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.36}" fill="var(--wood)" stroke="${color}" stroke-width="${inTree ? 3 : 1.5}"/>`);
      parts.push(`<text x="${X(x)}" y="${Y(y) + 5}" font-size="14" font-weight="700" text-anchor="middle" fill="${color}">${i + 1}</text>`);
    });
    for (const m of expect.choose) {
      if (m === END || labeled.has(m)) continue;
      const [x, y] = gtpXY(pt(m));
      if (!inView(x, y)) continue;
      parts.push(`<circle cx="${X(x)}" cy="${Y(y)}" r="${cell * 0.36}" fill="none" stroke="var(--good)" stroke-width="3"/>`);
    }
    for (const m of expect.avoid) {
      if (m === END || labeled.has(m)) continue;
      const [x, y] = gtpXY(pt(m));
      if (!inView(x, y)) continue;
      const r = cell * 0.25;
      parts.push(`<path d="M${X(x) - r} ${Y(y) - r}L${X(x) + r} ${Y(y) + r}M${X(x) + r} ${Y(y) - r}L${X(x) - r} ${Y(y) + r}" stroke="var(--bad)" stroke-width="3"/>`);
    }
  }
  parts.push('</svg>');
  const holder = $('board');
  holder.innerHTML = parts.join('');
  holder.querySelector('svg').onclick = (ev) => {
    if (!d) return;
    const svg = ev.currentTarget;
    const p = svg.createSVGPoint();
    p.x = ev.clientX; p.y = ev.clientY;
    const loc = p.matrixTransform(svg.getScreenCTM().inverse());
    const x = Math.round((loc.x - margin) / cell) + x0, y = Math.round((loc.y - margin) / cell) + y0;
    if (!inView(x, y) || rows[y][x] !== '.') return;
    cycleMove(d.toMove + ' ' + COLS[x] + (19 - y));
  };

  const moveNo = node ? node.depth : 0;
  $('stepLabel').textContent = moveNo === 0 ? 'start position' : `after move ${moveNo}: ${lbl(node.move)}`;
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

  // lines first, under the stones
  for (const n of order) {
    if (!n.parent) continue;
    const color = status(n);
    const dash = n.gen ? '' : ' stroke-dasharray="5 4"';
    const px = X(n.parent), py = Y(n.parent.row), x = X(n), y = Y(n.row);
    const style = `stroke="${color}" stroke-width="3.2" stroke-linecap="round"${dash} fill="none"`;
    if (n.row === n.parent.row) {
      parts.push(`<line x1="${px}" y1="${py}" x2="${x}" y2="${y}" ${style}/>`);
    } else {
      parts.push(`<polyline points="${px},${py} ${px},${Y(n.row - 1)} ${x},${y}" ${style}/>`);
    }
  }

  for (const n of order) {
    const x = X(n), y = Y(n.row);
    const d = decisionAt(n.key);
    const c = caseAt(n.key);
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
    }
    // a case on the decision made here: green dot when this run passes it, red when not
    if (c && d) {
      const ok = passes(c, d);
      parts.push(`<circle cx="${x}" cy="${y}" r="3.2" fill="${ok ? '#22c55e' : '#ef4444'}" stroke="${n.move && n.move[0] === 'B' ? '#fff' : '#000'}" stroke-width="0.6"/>`);
      tip.push(ok ? 'case passes' : 'case fails');
    }
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
  let head = `<b>Correct tree</b> · generated ${lines.length} line${lines.length === 1 ? '' : 's'}, author ${rec.refs.length}`;
  if (t) head += `, ${t.reproduced} reproduced`;
  if (accepted) head += ' · <span class="badge good">accepted</span>';
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
  renderDecision();
}

function renderDecision() {
  const el = $('decision');
  const node = state.tree.index[state.sel];
  const d = decisionAt(state.sel);
  const author = authorMoves(state.sel);
  const authorText = author.length ? 'Author here: ' + author.map(lbl).join(', ') : 'Off the author\'s correct tree';
  if (!d) {
    el.innerHTML = `<h3>${node.move ? `${node.depth}. ${esc(pt(node.move))}` : 'Start'}</h3>
      <div class="reason">The generator never reached this position.<br>${esc(authorText)}</div>`;
    return;
  }
  const c = caseAt(state.sel);
  const expect = c ? c.expect : { choose: [], avoid: [] };
  const chosen = chosenMoves(d);
  const solver = d.role === 'solver';
  const choseText = d.chose === END ? 'end the line here'
    : solver ? 'play ' + chosen.map(pt).join(', ') : 'answer ' + chosen.map(pt).join(', ');

  let status;
  if (!c) {
    status = '<div class="status none">No case yet. <kbd>g</kbd> right, <kbd>b</kbd> wrong, or mark moves in the table.</div>';
  } else {
    const ok = passes(c, d);
    const exp = [expect.choose.length ? (solver ? 'must be in the tree: ' : 'right: ') + expect.choose.map(lbl).join(', ') : '',
      expect.avoid.length ? 'wrong: ' + expect.avoid.map(lbl).join(', ') : ''].filter(Boolean).join(' · ');
    status = `<div class="status ${ok ? 'pass' : 'fail'}">${ok ? 'This run gets it right' : 'This run gets it wrong'}
      · ${esc(exp)} <span class="muted">(${esc(c.source)}${c.by && c.by !== c.source ? ', ' + esc(c.by) : ''})</span></div>`;
  }

  const isRight = chosen.every((m) => expect.choose.includes(m));
  const isWrong = expect.avoid.includes(d.chose);
  const endHereOn = !solver && d.chose !== END && isWrong && expect.choose.includes(END);
  const actions = [
    `<button class="good ${isRight ? 'on' : ''}" data-act="good" title="${solver ? 'every move the tree plays here works' : 'the chosen response is right'}">Right <kbd>g</kbd></button>`,
    `<button class="bad ${isWrong && !endHereOn ? 'on' : ''}" data-act="bad">${d.chose === END ? 'Should continue' : 'Wrong'} <kbd>b</kbd></button>`,
    !solver && d.chose !== END ? `<button class="bad ${endHereOn ? 'on' : ''}" data-act="end">Should end here <kbd>e</kbd></button>` : '',
    `<button data-act="clear">Clear <kbd>x</kbd></button>`,
    c ? `<button data-act="delete">Delete case <kbd>d</kbd></button>` : '',
    `<button data-act="accept" title="mark every unjudged decision right and keep the generated lines as acceptable">Accept tree <kbd>A</kbd></button>`,
  ].join('');

  const star = (m) => (author.includes(m) ? ' <span class="muted" title="the author plays this">★</span>' : '');
  const head = solver
    ? '<th></th><th class="l">#</th><th class="l">move</th><th class="l">from</th><th>prior</th><th>visits</th><th>human</th><th>lead after</th><th>in tree</th>'
    : '<th></th><th class="l">#</th><th class="l">move</th><th class="l">from</th><th>prior</th><th>visits</th><th>human</th><th>threat</th><th class="l">answer</th><th>ans prior</th><th>human ans</th><th title="solver answers that hold the result">answers</th><th>stake</th>';
  const rows = d.candidates.map((cand, i) => {
    const mark = expect.choose.includes(cand.move) ? '<td class="mark good">✓</td>'
      : expect.avoid.includes(cand.move) ? '<td class="mark bad">✗</td>' : '<td class="mark"></td>';
    const cls = chosen.includes(cand.move) ? 'chosen' : '';
    const cells = solver
      ? `<td class="l">${esc(pt(cand.move))}${star(cand.move)}</td><td class="l dim">${esc(cand.source)}</td>
         <td>${fmt(cand.prior)}</td><td>${fmt(cand.visits)}</td><td>${fmt(cand.humanPolicy)}</td>
         <td>${cand.error ? 'err' : cand.afterLead === undefined || cand.afterLead === null ? '<span class="dim">–</span>' : fmt(cand.afterLead, 1)}</td>
         <td>${chosen.includes(cand.move) ? (cand.move === d.chose ? 'main' : 'yes') : ''}</td>`
      : `<td class="l">${esc(pt(cand.move))}${star(cand.move)}</td>
         <td class="l dim">${esc(cand.source)}</td><td>${fmt(cand.prior)}</td><td>${fmt(cand.visits)}</td>
         <td>${fmt(cand.humanPolicy)}</td><td>${cand.error ? 'err' : fmt(cand.threat)}</td><td class="l">${esc(cand.answer || '')}</td>
         <td>${fmt(cand.answerPrior)}</td><td>${fmt(cand.humanAnswer)}</td><td>${fmt(cand.answers)}</td><td>${fmt(cand.stakeDist)}</td>`;
    return `<tr class="${cls}" data-move="${esc(cand.move)}">${mark}<td class="l dim">${i + 1}</td>${cells}</tr>`;
  });
  if (!solver) {
    const mark = expect.choose.includes(END) ? '<td class="mark good">✓</td>'
      : expect.avoid.includes(END) ? '<td class="mark bad">✗</td>' : '<td class="mark"></td>';
    rows.push(`<tr class="${d.chose === END ? 'chosen' : ''}" data-move="${END}">${mark}<td class="l dim">0</td>
      <td class="l" colspan="12">end the line${star(END)}</td></tr>`);
  }
  const help = solver
    ? 'Every move that works must be in the tree. Click a row (or press its number, or click the board) to cycle it: works ✓, fails ✗, unjudged. ★ marks the author\'s moves.'
    : 'Click a row (or press its number, or click the board) to cycle it: acceptable ✓, wrong ✗, unjudged. ★ marks the author\'s moves.';

  const tags = [...new Set([...TAGS, ...((c && c.tags) || [])])].map((tg) =>
    `<button class="tag ${c && c.tags.includes(tg) ? 'on' : ''}" data-tag="${esc(tg)}">${esc(tg)}</button>`).join('');

  el.innerHTML = `
    <h3>${node.move ? `After ${node.depth}. ${esc(pt(node.move))} · ` : ''}${colorName(d.toMove)} to play · ${esc(d.role)}</h3>
    <div class="reason">Chose to ${esc(choseText)}: ${esc(d.reason || '')}<br>${esc(authorText)}</div>
    ${status}
    <div class="actions">${actions}</div>
    <table class="cands"><thead><tr>${head}</tr></thead><tbody>${rows.join('')}</tbody></table>
    <div class="muted" style="font-size:12px">${help}</div>
    <div class="section-label">Tags</div>
    <div class="tags">${tags}<input id="tagInput" placeholder="new tag…" aria-label="new tag"></div>
    <div class="section-label">Note</div>
    <textarea id="noteInput" placeholder="why, in a sentence">${esc((c && c.note) || '')}</textarea>`;

  el.querySelectorAll('[data-act]').forEach((b) => { b.onclick = () => act(b.dataset.act); });
  el.querySelectorAll('tr[data-move]').forEach((tr) => { tr.onclick = () => cycleMove(tr.dataset.move); });
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

// ---------- actions ----------

function currentEdit() {
  const d = decisionAt(state.sel);
  const c = caseAt(state.sel);
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
  if (kind === 'accept') return acceptTree();
  const e = currentEdit();
  if (!e.d) return;
  const chose = e.d.chose;
  const chosen = chosenMoves(e.d);
  if (kind === 'delete') {
    if (!e.exists) return;
    state.data.cases = await api(`/api/case-delete?name=${encodeURIComponent(state.name)}&key=${encodeURIComponent(state.sel)}`, { method: 'POST' });
    return afterSave('case deleted');
  }
  if (kind === 'good') {
    // right: everything the tree plays here (for the solver, every move kept as working)
    const on = chosen.every((m) => e.expect.choose.includes(m));
    for (const m of chosen) {
      e.expect.choose = without(e.expect.choose, m);
      e.expect.avoid = without(e.expect.avoid, m);
      if (!on) e.expect.choose.push(m);
    }
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
    for (const m of chosen) {
      e.expect.choose = without(e.expect.choose, m);
      e.expect.avoid = without(e.expect.avoid, m);
    }
  }
  await saveEdit(e);
}

// cycle a move: unjudged -> right -> wrong -> unjudged
async function cycleMove(move) {
  const e = currentEdit();
  if (!e.d) return;
  if (e.expect.choose.includes(move)) {
    e.expect.choose = without(e.expect.choose, move);
    e.expect.avoid.push(move);
  } else if (e.expect.avoid.includes(move)) {
    e.expect.avoid = without(e.expect.avoid, move);
  } else {
    e.expect.choose.push(move);
  }
  await saveEdit(e);
}

async function toggleTag(tag) {
  const e = currentEdit();
  if (!e.d) return;
  e.tags = e.tags.includes(tag) ? without(e.tags, tag) : [...e.tags, tag];
  await saveEdit(e);
}

async function save(fields) {
  const e = currentEdit();
  if (!e.d) return;
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
  const order = state.tree.order;
  const ni = order.findIndex((n) => n.key === state.sel);
  if (k === 'ArrowRight' && ni < order.length - 1) select(order[ni + 1].key);
  else if (k === 'ArrowLeft' && ni > 0) select(order[ni - 1].key);
  else if (k === 'Home') select('root');
  else if (k === 'ArrowDown' && pi < problems.length - 1) openProblem(problems[pi + 1].problem);
  else if (k === 'ArrowUp' && pi > 0) openProblem(problems[pi - 1].problem);
  else if (k === 'g') act('good');
  else if (k === 'b') act('bad');
  else if (k === 'e') act('end');
  else if (k === 'x') act('clear');
  else if (k === 'd') act('delete');
  else if (k === 'A') act('accept');
  else if (k === 's') { state.stakes = !state.stakes; renderBoard(); }
  else if (k === 'f') { state.full = !state.full; renderBoard(); }
  else if (k === '?') $('help').showModal();
  else if (/^[0-9]$/.test(k)) {
    const d = decisionAt(state.sel);
    if (!d) return;
    if (k === '0') { if (d.role === 'opponent') cycleMove(END); }
    else if (d.candidates[+k - 1]) cycleMove(d.candidates[+k - 1].move);
  } else return;
  ev.preventDefault();
});

$('showStakes').onchange = (ev) => { state.stakes = ev.target.checked; renderBoard(); };
$('fullBoard').onchange = (ev) => { state.full = ev.target.checked; renderBoard(); };
$('helpBtn').onclick = () => $('help').showModal();

init();
