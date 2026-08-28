#!/usr/bin/env python3
# Benchmark katago analysis latency with the redo workload's query mix.
# Replays a captured request (from analysis_logs) with a random extra move per
# query so every position is fresh and the nn cache cannot short-circuit.
# Detects stalls (>60s) and probes them with a tiny nudge query to see whether
# a pending response flushes. No Java involved: pure pipes.
#
# usage: python3 tools/kata_latency_bench.py <request.json> [n_queries] [label]
import json, subprocess, sys, time, threading, queue, copy, random

KATAGO = "/opt/homebrew/bin/katago"
CFG = "config/redo_analysis.cfg"
MODEL = "/Users/adammiller/Downloads/kata1-b28c512nbt-s7332806912-d4357057652.bin.gz"
HUMAN = "/Users/adammiller/Downloads/b18c384nbt-humanv0.bin.gz"
SAMPLE = sys.argv[1]
N = int(sys.argv[2]) if len(sys.argv) > 2 else 36
LABEL = sys.argv[3] if len(sys.argv) > 3 else "bench"
STALL_S = 60
GIVEUP_S = 600

base = json.load(open(SAMPLE))
proc = subprocess.Popen([KATAGO, "analysis", "-config", CFG, "-model", MODEL, "-human-model", HUMAN],
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=open(f"{SAMPLE}.{LABEL}.stderr", "w"), text=True, bufsize=1)
lines = queue.Queue()
def reader():
    for line in proc.stdout:
        lines.put((time.time(), line))
    lines.put((time.time(), None))
threading.Thread(target=reader, daemon=True).start()

def log(msg):
    print(f"{time.strftime('%H:%M:%S')} {msg}", flush=True)

def send(q):
    proc.stdin.write(json.dumps(q) + "\n")
    proc.stdin.flush()

def wait_for(qid, deadline_s):
    t0 = time.time()
    nudged_at = None
    outcome = "ok"
    while True:
        try:
            ts, line = lines.get(timeout=1.0)
        except queue.Empty:
            waited = time.time() - t0
            if nudged_at is None and waited > STALL_S:
                log(f"  STALL: {waited:.0f}s silent on {qid}, sending nudge")
                send({"id": "nudge" + str(time.time()), "initialStones": [], "moves": [],
                      "rules": "tromp-taylor", "komi": 7.5, "boardXSize": 19, "boardYSize": 19,
                      "analyzeTurns": [0], "maxVisits": 1})
                nudged_at = time.time()
                outcome = "stalled"
            if waited > deadline_s:
                return "gaveup", time.time() - t0, nudged_at
            continue
        if line is None:
            return "engine_died", time.time() - t0, nudged_at
        if not line.startswith("{"):
            continue
        try:
            r = json.loads(line)
        except Exception:
            log(f"  UNPARSEABLE line ({len(line)} bytes): {line[:80]}")
            continue
        if r.get("id", "") == qid:
            if nudged_at is not None:
                dt = time.time() - nudged_at
                log(f"  stalled response arrived {dt:.1f}s after nudge")
                outcome = f"stalled_then_{'flushed_by_nudge' if dt < 5 else 'late'}"
            return outcome, time.time() - t0, nudged_at

while True:
    ts, line = lines.get()
    if line is None: sys.exit("engine died at startup")
    if "ready to begin handling requests" in line.lower():
        break
log(f"engine ready ({LABEL})")

taken = set(st[1] for st in base["initialStones"])
cols = "ABCDEFGHJKLMNOPQRST"
empties = [c + str(r) for c in cols for r in range(1, 20) if c + str(r) not in taken]
random.seed(7)
lat = []
stalls = 0
for i in range(N):
    q = copy.deepcopy(base)
    q["id"] = f"bench{i}"
    mover = q.get("initialPlayer", "B")
    q["moves"] = [[mover, random.choice(empties)]]
    q["analyzeTurns"] = [1]
    kind = i % 6
    if kind < 3: q["maxVisits"] = 500
    elif kind < 5: q["maxVisits"] = 2000
    else:
        q["maxVisits"] = 1
        q["overrideSettings"] = {"humanSLProfile": "preaz_5k"}
    send(q)
    outcome, dt, nudged = wait_for(q["id"], GIVEUP_S)
    lat.append(dt)
    if outcome != "ok": stalls += 1
    log(f"query {i} (visits {q['maxVisits']}): {outcome} in {dt:.1f}s")

log(f"DONE {LABEL}: {N} queries, stalls: {stalls}, median {sorted(lat)[len(lat)//2]:.1f}s, max {max(lat):.1f}s")
proc.terminate()
