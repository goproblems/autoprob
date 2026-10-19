#!/usr/bin/env python3
"""Review fuseki problem candidates in a browser and keep the feedback in the repo.

Serves a local page that shows each candidate (board, the two choices, the scanner's analysis),
takes an optional blind pick, a verdict, tags and a free-text reason, and writes one record per
candidate to feedback/fuseki/<id>.json: the candidate json as the scanner wrote it, plus a list
of reviews. Finishing the session (the button, or Ctrl-C) commits feedback/fuseki/.
Record schema and tag meanings: feedback/fuseki/README.md.

usage: python3 tools/fuseki_review.py [--port 8765] [--reviewer NAME] [--no-open] [candidate dirs...]
       candidate dirs default to fuseki_out/problems fuseki_out/near_misses fuseki_out/review_sample
"""
import argparse
import collections
import datetime
import glob
import http.server
import json
import os
import subprocess
import sys
import urllib.parse
import webbrowser

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FEEDBACK = os.path.join(REPO, "feedback", "fuseki")  # --feedback-dir changes it, e.g. for a dry run
DEFAULT_DIRS = ["fuseki_out/problems", "fuseki_out/near_misses", "fuseki_out/review_sample"]
KIND_ORDER = {"problem": 0, "near_miss": 1, "rejected": 2}
VERDICTS = ["good", "fixable", "bad", "unsure"]
# starting vocabulary; feedback/fuseki/README.md explains each, and free-form extra tags are allowed
TAGS = {
    "bad": ["implausible-mistake", "tactical", "not-fuseki", "local-shape", "both-playable",
            "answer-unnatural", "ambiguous", "too-easy", "duplicate-lesson", "engine-wrong"],
    "good": ["instructive", "nice-trap", "classic-lesson"],
}

state = {"cands": [], "by_id": {}, "reviewer": "", "saved": {}}


def load_candidates(dirs):
    cands = {}
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(REPO, d, "*.json"))):
            try:
                c = json.load(open(path))
            except ValueError:
                continue
            if "id" not in c or "problem" not in c:
                continue
            c["_sgf_path"] = path[:-5] + ".sgf"
            prev = cands.get(c["id"])
            if prev is None or KIND_ORDER.get(c.get("kind"), 9) < KIND_ORDER.get(prev.get("kind"), 9):
                cands[c["id"]] = c
    return sorted(cands.values(), key=lambda c: (KIND_ORDER.get(c.get("kind"), 9), c["id"]))


def snapshot(c):
    """The candidate as generated, without this tool's bookkeeping."""
    return {k: v for k, v in c.items() if not k.startswith("_")}


def feedback_path(cid):
    return os.path.join(FEEDBACK, cid + ".json")


def load_feedback(cid):
    p = feedback_path(cid)
    return json.load(open(p)) if os.path.exists(p) else None


def my_review(rec):
    for r in (rec or {}).get("reviews", []):
        if r.get("reviewer") == state["reviewer"]:
            return r
    return None


def save_feedback(body):
    c = state["by_id"].get(body.get("id"))
    if c is None:
        return {"ok": False, "error": "unknown candidate"}
    if body.get("verdict") not in VERDICTS:
        return {"ok": False, "error": "verdict must be one of " + ", ".join(VERDICTS)}
    pick = body.get("blind_pick")
    review = {
        "reviewer": state["reviewer"],
        "date": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
        "verdict": body["verdict"],
        "tags": sorted({t.strip() for t in body.get("tags", []) if t.strip()}),
        "why": body.get("why", "").strip(),
        "blind_pick": pick,
        "blind_correct": None if pick is None else pick == c["problem"].get("correct_label"),
    }
    rec = load_feedback(c["id"]) or dict(snapshot(c), reviews=[])
    rec["reviews"] = [r for r in rec.get("reviews", []) if r.get("reviewer") != state["reviewer"]] + [review]
    os.makedirs(FEEDBACK, exist_ok=True)
    with open(feedback_path(c["id"]), "w") as w:
        w.write(json.dumps(rec, indent=2) + "\n")
    state["saved"][c["id"]] = review["verdict"]
    return {"ok": True, "file": os.path.relpath(feedback_path(c["id"]), REPO)}


def commit():
    saved = state["saved"]
    if not saved:
        return {"ok": True, "message": "nothing new to commit"}
    counts = collections.Counter(saved.values())
    msg = "Fuseki feedback: %d review%s (%s)" % (
        len(saved), "" if len(saved) == 1 else "s", ", ".join("%d %s" % (counts[v], v) for v in VERDICTS if counts[v]))
    rel = os.path.relpath(FEEDBACK, REPO)
    if rel.startswith(".."):
        return {"ok": True, "message": "feedback dir is outside the repo, not committing (%d reviews saved)" % len(saved)}
    subprocess.run(["git", "-C", REPO, "add", rel], check=True)
    r = subprocess.run(["git", "-C", REPO, "commit", "-m", msg, "--", rel], capture_output=True, text=True)
    if r.returncode != 0:
        return {"ok": False, "message": "git commit failed: " + (r.stdout + r.stderr).strip()}
    state["saved"] = {}
    branch = subprocess.run(["git", "-C", REPO, "branch", "--show-current"], capture_output=True, text=True).stdout.strip()
    return {"ok": True, "message": "committed on %s: %s" % (branch, msg)}


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def reply(self, body, ctype="application/json", code=200):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(url.query)
        if url.path == "/":
            self.reply(PAGE.encode(), "text/html; charset=utf-8")
        elif url.path == "/api/queue":
            items = []
            for c in state["cands"]:
                r = my_review(load_feedback(c["id"]))
                items.append({"id": c["id"], "kind": c.get("kind"), "verdict": r["verdict"] if r else None})
            self.reply({"queue": items, "reviewer": state["reviewer"], "tags": TAGS,
                        "saved": len(state["saved"])})
        elif url.path == "/api/candidate":
            c = state["by_id"].get((q.get("id") or [""])[0])
            if c is None:
                self.reply({"error": "unknown candidate"}, code=404)
                return
            rec = load_feedback(c["id"])
            others = [r for r in (rec or {}).get("reviews", []) if r.get("reviewer") != state["reviewer"]]
            self.reply({"candidate": snapshot(c), "review": my_review(rec), "others": others})
        else:
            self.reply({"error": "not found"}, code=404)

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            self.reply({"ok": False, "error": "bad json"}, code=400)
            return
        if url.path == "/api/feedback":
            self.reply(save_feedback(body))
        elif url.path == "/api/open":
            c = state["by_id"].get(body.get("id"))
            if c is None or not os.path.exists(c["_sgf_path"]):
                self.reply({"ok": False, "error": "no sgf file for this candidate"})
                return
            subprocess.run(["open", c["_sgf_path"]])
            self.reply({"ok": True})
        elif url.path == "/api/commit":
            self.reply(commit())
        else:
            self.reply({"error": "not found"}, code=404)


def main():
    ap = argparse.ArgumentParser(description="Review fuseki problem candidates and keep the feedback in the repo.")
    ap.add_argument("dirs", nargs="*", default=DEFAULT_DIRS, help="folders of candidate .json/.sgf pairs")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--reviewer", default=None, help="name stored with each review (default: git user.name)")
    ap.add_argument("--no-open", action="store_true", help="do not open a browser")
    ap.add_argument("--feedback-dir", default=None, help="where records go (default feedback/fuseki)")
    args = ap.parse_args()
    global FEEDBACK
    if args.feedback_dir:
        FEEDBACK = os.path.abspath(args.feedback_dir)
    state["reviewer"] = args.reviewer or subprocess.run(
        ["git", "-C", REPO, "config", "user.name"], capture_output=True, text=True).stdout.strip() or "reviewer"
    state["cands"] = load_candidates(args.dirs)
    state["by_id"] = {c["id"]: c for c in state["cands"]}
    if not state["cands"]:
        sys.exit("no candidates found in " + ", ".join(args.dirs))
    server = http.server.HTTPServer(("127.0.0.1", args.port), Handler)
    url = "http://127.0.0.1:%d/" % args.port
    print("%d candidates from %s; reviewing as %s" % (len(state["cands"]), ", ".join(args.dirs), state["reviewer"]), flush=True)
    print("open %s  (Ctrl-C ends the session and commits %s/)" % (url, os.path.relpath(FEEDBACK, REPO)), flush=True)
    if not args.no_open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()
        print(commit()["message"])


PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Fuseki review</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root { --bg:#f6f5f2; --panel:#fff; --ink:#1d1d1f; --muted:#6b6b70; --line:#e2e0da; --accent:#2563eb;
        --good:#15803d; --bad:#b91c1c; --warn:#b45309; }
* { box-sizing:border-box; }
body { margin:0; font:14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background:var(--bg); color:var(--ink); }
header { display:flex; align-items:center; gap:14px; padding:10px 18px; border-bottom:1px solid var(--line); background:var(--panel); flex-wrap:wrap; }
header h1 { font-size:15px; margin:0; }
.grow { flex:1; }
main { display:flex; gap:18px; padding:18px; align-items:flex-start; flex-wrap:wrap; }
#boardwrap { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:10px; }
#board { width:min(640px, calc(100vw - 58px)); height:auto; display:block; }
#board text { font:11px -apple-system, sans-serif; fill:#5a4a32; text-anchor:middle; }
#board text.lbl { font:bold 15px -apple-system, sans-serif; fill:#fff; }
#side { flex:1; min-width:300px; max-width:560px; display:flex; flex-direction:column; gap:12px; }
.card { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:14px 16px; }
.kind { display:inline-block; font-size:12px; padding:1px 8px; border-radius:99px; background:#e0e7ff; color:#3730a3; margin-right:6px; }
.kind.near_miss { background:#fef3c7; color:#92400e; }
.kind.rejected { background:#fee2e2; color:#991b1b; }
.muted { color:var(--muted); }
.small { font-size:12px; }
button { font:inherit; border:1px solid var(--line); background:#fff; border-radius:8px; padding:6px 12px; cursor:pointer; color:var(--ink); }
button:hover { border-color:#b9b6ad; }
button.pick { font-size:18px; font-weight:600; width:64px; height:44px; margin-right:8px; }
.verdicts button.on { color:#fff; }
.verdicts button[data-v=good].on { background:var(--good); border-color:var(--good); }
.verdicts button[data-v=fixable].on { background:var(--warn); border-color:var(--warn); }
.verdicts button[data-v=bad].on { background:var(--bad); border-color:var(--bad); }
.verdicts button[data-v=unsure].on { background:#555; border-color:#555; }
.chips { display:flex; flex-wrap:wrap; gap:6px; margin:6px 0 10px; }
.chip { font-size:12.5px; padding:3px 9px; border-radius:99px; border:1px solid var(--line); cursor:pointer; user-select:none; background:#fff; }
.chip.on { background:var(--ink); color:#fff; border-color:var(--ink); }
.chip.good.on { background:var(--good); border-color:var(--good); }
textarea, input[type=text] { width:100%; font:inherit; padding:8px; border:1px solid var(--line); border-radius:8px; }
textarea { min-height:96px; resize:vertical; }
table.kv { border-collapse:collapse; width:100%; }
table.kv td { padding:3px 0; vertical-align:top; }
table.kv td:first-child { color:var(--muted); width:36%; padding-right:10px; }
.curve { display:grid; grid-template-columns:34px 1fr 34px; gap:3px 8px; font-size:12px; align-items:center; margin-top:8px; }
.bar { height:8px; background:#e5e7eb; border-radius:4px; position:relative; }
.bar > i { position:absolute; left:0; top:0; bottom:0; background:var(--accent); border-radius:4px; }
.bar::after { content:""; position:absolute; left:50%; top:-3px; bottom:-3px; border-left:1px dashed #9a978f; }
.row { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
h3 { margin:0 0 6px; font-size:13px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }
.ok { color:var(--good); font-weight:600; } .no { color:var(--bad); font-weight:600; }
kbd { font:11px ui-monospace, monospace; border:1px solid var(--line); border-bottom-width:2px; border-radius:4px; padding:0 4px; color:var(--muted); background:#fff; }
#toast { position:fixed; bottom:18px; left:50%; transform:translateX(-50%); background:var(--ink); color:#fff; padding:8px 14px; border-radius:8px; opacity:0; transition:opacity .2s; pointer-events:none; max-width:90vw; }
#toast.show { opacity:.95; }
</style></head>
<body>
<header>
  <h1>Fuseki review</h1><span id="progress" class="muted small"></span><span class="grow"></span>
  <label class="muted small"><input type="checkbox" id="showall"> include reviewed</label>
  <span id="who" class="muted small"></span>
  <button id="commit">Finish &amp; commit</button>
</header>
<main>
  <div id="boardwrap"><svg id="board" viewBox="0 0 660 660" role="img" aria-label="board"></svg></div>
  <div id="side">
    <div class="card" id="meta"></div>
    <div class="card" id="ask"></div>
    <div class="card" id="analysis" hidden></div>
    <div class="card" id="form" hidden>
      <h3>Verdict</h3>
      <div class="row verdicts">
        <button data-v="good">Good <kbd>g</kbd></button><button data-v="fixable">Fixable <kbd>f</kbd></button>
        <button data-v="bad">Bad <kbd>x</kbd></button><button data-v="unsure">Unsure <kbd>u</kbd></button>
      </div>
      <h3 style="margin-top:12px">Tags</h3>
      <div class="chips" id="chips"></div>
      <input type="text" id="extratags" placeholder="other tags, comma separated">
      <h3 style="margin-top:12px">Why <span class="muted small" style="text-transform:none;letter-spacing:0"><kbd>w</kbd> to type</span></h3>
      <textarea id="why" placeholder="What makes this a good or bad problem?"></textarea>
      <div class="row" style="margin-top:10px"><button id="save" style="background:var(--accent);border-color:var(--accent);color:#fff">Save &amp; next <kbd style="color:#dbe4ff;border-color:#7aa2f7;background:transparent">ctrl/cmd + enter</kbd></button>
      <span id="others" class="muted small"></span></div>
    </div>
    <div class="row"><button id="prev">&larr; Prev</button><button id="next">Next &rarr;</button><button id="opensgf">Open SGF</button>
      <span class="muted small">arrows move between candidates</span></div>
  </div>
</main>
<div id="toast"></div>
<script>
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
let queue = [], idx = -1, cur = null, stage = 'ask', pick = null, verdict = null, tags = new Set(), saved = 0, TAGS = {};

async function api(path, body) {
  const r = await fetch(path, body ? {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)} : {});
  return r.json();
}
function toast(msg) { const t = $('#toast'); t.textContent = msg; t.classList.add('show'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('show'), 2600); }

function parseRoot(sgf) {
  const root = sgf.split('\n(;')[0], props = {}, re = /([A-Z]{1,2})((?:\[[^\]]*\])+)/g;
  let m;
  while ((m = re.exec(root))) {
    const vals = [...m[2].matchAll(/\[([^\]]*)\]/g)].map(x => x[1]);
    (props[m[1]] = props[m[1]] || []).push(...vals);
  }
  return props;
}

function drawBoard() {
  const p = parseRoot(cur.problem.sgf), S = 32, M = 42, cols = 'ABCDEFGHJKLMNOPQRST';
  const at = c => [M + (c.charCodeAt(0) - 97) * S, M + (c.charCodeAt(1) - 97) * S];
  let h = `<rect width="660" height="660" rx="8" fill="#dcb35c"/>`;
  for (let i = 0; i < 19; i++) {
    h += `<line x1="${M}" y1="${M+i*S}" x2="${M+18*S}" y2="${M+i*S}" stroke="#3b2f1e"/><line x1="${M+i*S}" y1="${M}" x2="${M+i*S}" y2="${M+18*S}" stroke="#3b2f1e"/>`;
    h += `<text x="${M+i*S}" y="${M-20}">${cols[i]}</text><text x="${M+i*S}" y="${M+18*S+30}">${cols[i]}</text>`;
    h += `<text x="${M-24}" y="${M+i*S+4}">${19-i}</text><text x="${M+18*S+24}" y="${M+i*S+4}">${19-i}</text>`;
  }
  for (const a of [3, 9, 15]) for (const b of [3, 9, 15]) h += `<circle cx="${M+a*S}" cy="${M+b*S}" r="3.5" fill="#3b2f1e"/>`;
  for (const c of p.AB || []) { const [x, y] = at(c); h += `<circle cx="${x}" cy="${y}" r="14.5" fill="#151515"/>`; }
  for (const c of p.AW || []) { const [x, y] = at(c); h += `<circle cx="${x}" cy="${y}" r="14.5" fill="#f7f7f5" stroke="#222" stroke-width="1"/>`; }
  for (const c of p.TR || []) {
    const [x, y] = at(c), white = (p.AW || []).includes(c);
    h += `<circle cx="${x}" cy="${y}" r="6" fill="none" stroke="${white ? '#151515' : '#f7f7f5'}" stroke-width="2.2"/>`;
  }
  const revealed = stage === 'reveal';
  for (const v of p.LB || []) {
    const [c, l] = v.split(':'), [x, y] = at(c);
    const color = !revealed ? '#2563eb' : (l === cur.problem.correct_label ? '#15803d' : '#b91c1c');
    h += `<circle cx="${x}" cy="${y}" r="14" fill="${color}" stroke="#fff" stroke-width="1.5"/><text class="lbl" x="${x}" y="${y+5.5}">${esc(l)}</text>`;
  }
  $('#board').innerHTML = h;
}

const toPlay = () => (cur.source.to_play === 'B' ? 'Black' : 'White');
const pct = v => v == null ? '?' : (100 * v).toFixed(1) + '%';
const pts = v => v == null ? '?' : Number(v).toFixed(1);

function render() {
  const s = cur.source, pr = cur.problem, m = cur.metrics || {};
  const kindName = {problem:'Problem', near_miss:'Near miss', rejected:'Rejected sample'}[cur.kind] || cur.kind;
  $('#meta').innerHTML = `<span class="kind ${esc(cur.kind)}">${esc(kindName)}</span><b>${esc(cur.id)}</b>
    <div class="muted small">${toPlay()} to play, move ${s.move} &middot; ${esc(s.black || '?')} ${esc(s.black_rank || '')} (B) vs ${esc(s.white || '?')} ${esc(s.white_rank || '')} (W) &middot; ${esc(s.date || '')}</div>
    <div class="muted small">${esc(s.path)}</div>`;
  if (stage === 'ask') {
    $('#ask').innerHTML = `<b>${toPlay()} to play. Which is better?</b>
      <div class="row" style="margin-top:10px"><button class="pick" data-p="A">A</button><button class="pick" data-p="B">B</button>
      <span class="muted small">pick <kbd>a</kbd> / <kbd>b</kbd>, or <a href="#" id="skip">skip</a> <kbd>space</kbd></span></div>`;
    document.querySelectorAll('button.pick').forEach(b => b.onclick = () => choose(b.dataset.p));
    $('#skip').onclick = e => { e.preventDefault(); reveal(); };
  } else {
    $('#ask').innerHTML = pick == null ? `<span class="muted">No blind pick.</span>`
      : `Your pick: <b>${pick}</b> ${pick === pr.correct_label ? '<span class="ok">correct</span>' : '<span class="no">wrong</span>'}`;
  }
  $('#analysis').hidden = $('#form').hidden = stage !== 'reveal';
  if (stage === 'reveal') renderAnalysis(pr, m);
  document.querySelectorAll('.verdicts button').forEach(b => b.classList.toggle('on', b.dataset.v === verdict));
  document.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', tags.has(c.dataset.t)));
  drawBoard();
}

function renderAnalysis(pr, m) {
  const rows = [
    ['Correct', `<b>${esc(pr.correct_label)} = ${esc(pr.correct)}</b>`],
    ['Mistake', `${esc(pr.mistake_label)} = ${esc(pr.mistake)} (the game move)`],
    ['Gap', m.gap_strong != null ? `${pts(m.gap_strong)} strong / ${pts(m.gap_fast)} fast` : `${pts(m.gap_fast)} fast`],
  ];
  if (m.strong_best) rows.push(['Strong net best', `${esc(m.strong_best)}, answer ${pts(m.strong_answer_loss)} behind`]);
  rows.push(['Good moves (fast)', esc(m.good_moves) + (m.good_ideas != null ? ` &middot; ${m.good_ideas} idea${m.good_ideas === 1 ? '' : 's'}` : '')]);
  rows.push(['Game move at ' + esc(m.mover_rank || '?'), `${pct(m.mistake_human_policy)} of players${m.mistake_human_order ? `, choice #${m.mistake_human_order}` : ''}`]);
  if (m.choice_distance != null) rows.push(['Choice distance', m.choice_distance]);
  rows.push(['Calm', `score ${pts(m.score_lead)} for the mover &middot; stone stdev ${m.stone_stdev_mean ?? '?'} / ${m.stone_stdev_max ?? '?'} &middot; ${esc(m.lines || '')}`]);
  if (cur.kind !== 'problem' && m.reasons) rows.push(['Why it was not accepted', esc(m.reasons)]);
  let curve = '';
  for (const [r, v] of Object.entries(m.curve || {}))
    curve += `<span>${esc(r)}</span><div class="bar"><i style="width:${(100 * v).toFixed(0)}%"></i></div><span>${v.toFixed(2)}</span>`;
  $('#analysis').innerHTML = `<h3>Analysis</h3><table class="kv">${rows.map(r => `<tr><td>${r[0]}</td><td>${r[1]}</td></tr>`).join('')}</table>
    <h3 style="margin-top:10px">Picks the correct move, by rank</h3><div class="muted small">crosses 50%: ${esc(m.crossover || '?')}</div>
    <div class="curve">${curve}</div>`;
}

function choose(p) { pick = p; reveal(); }
function reveal() { stage = 'reveal'; render(); }
function setVerdict(v) { verdict = v; render(); }

function chipsInit() {
  let h = '';
  for (const t of TAGS.bad || []) h += `<span class="chip" data-t="${esc(t)}">${esc(t)}</span>`;
  for (const t of TAGS.good || []) h += `<span class="chip good" data-t="${esc(t)}">${esc(t)}</span>`;
  $('#chips').innerHTML = h;
  document.querySelectorAll('.chip').forEach(c => c.onclick = () => {
    tags.has(c.dataset.t) ? tags.delete(c.dataset.t) : tags.add(c.dataset.t); render();
  });
}

async function openAt(i) {
  if (i < 0 || i >= queue.length) return;
  idx = i;
  const d = await api('/api/candidate?id=' + encodeURIComponent(queue[i].id));
  cur = d.candidate;
  const r = d.review;
  stage = r ? 'reveal' : 'ask';
  pick = r ? r.blind_pick : null;
  verdict = r ? r.verdict : null;
  const known = new Set([...(TAGS.bad || []), ...(TAGS.good || [])]);
  tags = new Set((r ? r.tags : []).filter(t => known.has(t)));
  $('#extratags').value = (r ? r.tags : []).filter(t => !known.has(t)).join(', ');
  $('#why').value = r ? r.why : '';
  $('#others').textContent = d.others.length ? `also reviewed by ${d.others.map(o => o.reviewer + ' (' + o.verdict + ')').join(', ')}` : '';
  render();
  progress();
  window.scrollTo(0, 0);
}

// forward skips reviewed candidates unless "include reviewed" is on; back visits everything, so a
// review just saved can be corrected
function step(dir) {
  const all = $('#showall').checked || dir < 0;
  for (let i = idx + dir; i >= 0 && i < queue.length; i += dir)
    if (all || !queue[i].verdict) return openAt(i);
  toast(dir > 0 ? 'No more candidates to review' : 'At the start of the queue');
}

function progress() {
  const done = queue.filter(q => q.verdict).length;
  $('#progress').textContent = `${queue.length - done} to review · ${done} reviewed · ${saved} saved this session` + (idx >= 0 ? ` · #${idx + 1} of ${queue.length}` : '');
}

async function save() {
  if (!cur || stage !== 'reveal') return;
  if (!verdict) { toast('Pick a verdict first: g / f / x / u'); return; }
  const extra = $('#extratags').value.split(',').map(s => s.trim()).filter(Boolean);
  const res = await api('/api/feedback', {id: cur.id, verdict, tags: [...tags, ...extra], why: $('#why').value, blind_pick: pick});
  if (!res.ok) { toast(res.error); return; }
  if (!queue[idx].verdict) saved++;
  queue[idx].verdict = verdict;
  toast('Saved ' + res.file);
  step(1);
  progress();
}

document.addEventListener('keydown', e => {
  const el = document.activeElement, typing = el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && el.type === 'text'));
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); save(); return; }
  if (typing) { if (e.key === 'Escape') el.blur(); return; }
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  if (e.key === 'ArrowRight') { step(1); return; }
  if (e.key === 'ArrowLeft') { step(-1); return; }
  if (!cur) return;
  if (stage === 'ask') {
    if (e.key === 'a' || e.key === 'b') choose(e.key.toUpperCase());
    else if (e.key === ' ') { e.preventDefault(); reveal(); }
  } else {
    const v = {g:'good', f:'fixable', x:'bad', u:'unsure'}[e.key];
    if (v) setVerdict(v);
    if (e.key === 'w') { e.preventDefault(); $('#why').focus(); }
  }
});
document.querySelectorAll('.verdicts button').forEach(b => b.onclick = () => setVerdict(b.dataset.v));
$('#save').onclick = save;
$('#next').onclick = () => step(1);
$('#prev').onclick = () => step(-1);
$('#opensgf').onclick = async () => { if (cur) { const r = await api('/api/open', {id: cur.id}); if (!r.ok) toast(r.error); } };
$('#commit').onclick = async () => { const r = await api('/api/commit', {}); toast(r.message); if (r.ok) { saved = 0; progress(); } };
$('#showall').onchange = progress;

(async () => {
  const d = await api('/api/queue');
  queue = d.queue; TAGS = d.tags; saved = d.saved;
  $('#who').textContent = 'reviewing as ' + d.reviewer;
  chipsInit();
  const first = queue.findIndex(q => !q.verdict);
  if (first >= 0) openAt(first); else if (queue.length) { $('#showall').checked = true; openAt(0); toast('Everything here is reviewed'); }
})();
</script>
</body></html>
"""

if __name__ == "__main__":
    main()
