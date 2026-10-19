package autoprob.fuseki;

import java.awt.Point;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import autoprob.fuseki.FusekiScanner.Candidate;
import autoprob.fuseki.FusekiScanner.Game;
import autoprob.fuseki.FusekiScanner.Position;
import autoprob.fuseki.FusekiScanner.Reason;
import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.CommentAction;
import autoprob.go.action.LabelAction;
import autoprob.go.action.TriangleAction;

import static autoprob.fuseki.FusekiScanner.fmt;

// writes the scan results for people: report.md (funnel, every problem with a
// board diagram), positions.csv (one row per scanned position, for tuning), and
// one-move-deep A/B problem sgfs split into accepted/ and rejected/
public class FusekiReport {
    private final FusekiScanner scan;
    private final File outDir;

    public FusekiReport(FusekiScanner scan, File outDir) {
        this.scan = scan;
        this.outDir = outDir;
    }

    // status is shown at the top of the report; sgfs (and the shared folders) only once candidates are verified
    public void write(boolean withSgfs, String status) throws Exception {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new RuntimeException("cannot create output directory: " + outDir);
        }
        if (withSgfs) {
            writeSgfs();
        }
        writeCsv();
        writeMarkdown(withSgfs, status);
        System.out.println("wrote " + new File(outDir, "report.md").getPath() + " (" + status + ")");
    }

    private void writeSgfs() throws Exception {
        for (Position pos : scan.positions) {
            if (pos.answer == null || pos.stage < FusekiScanner.STAGE_CALM) continue;
            File dir = new File(outDir, pos.accepted() ? "accepted" : scan.nearMiss(pos) ? "near_misses" : "rejected");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new RuntimeException("cannot create output directory: " + dir);
            }
            File f = new File(dir, pos.id() + ".sgf");
            String sgf = problemSgf(pos);
            try (PrintWriter w = new PrintWriter(f)) {
                w.println(sgf);
            }
            pos.sgfPath = dir.getName() + "/" + f.getName();
            if (pos.accepted()) {
                track(scan.problemsDir(), PROBLEMS_HEADER, pos, f.getName(), sgf);
            } else if (scan.nearMiss(pos)) {
                track(scan.nearDir(), NEAR_HEADER, pos, f.getName(), sgf);
            }
        }
    }

    static final String INDEX_COLUMNS = "| SGF | Position | To play | Correct | Game move (mistake) | Gap strong / fast | Ranks B/W | Run |\n"
            + "|---|---|---|---|---|---|---|---|\n";
    static final String PROBLEMS_HEADER = "# Fuseki problems\n\n"
            + "Accepted problems from every scan run: calm openings where the game move lost to a natural good move by"
            + " at least that run's bar (fuseki.min_gap, strong net). Each SGF is a one-move-deep A/B problem; the root"
            + " comment has the analysis. Rows are added as runs find them; the gap column shows each problem's margin.\n\n" + INDEX_COLUMNS;
    static final String NEAR_HEADER = "# Fuseki near misses\n\n"
            + "Positions that passed every check except the last: the strong net gap between the correct move and the"
            + " game move came out just under that run's bar (between fuseki.near_gap_min and fuseki.min_gap). Each SGF"
            + " is a one-move-deep A/B problem; the root comment has the analysis. Rows are added as runs find them.\n\n" + INDEX_COLUMNS;

    // problems and near misses from every run also collect in shared folders with an index, for review across runs
    private void track(String dirName, String header, Position pos, String fileName, String sgf) throws Exception {
        File dir = new File(dirName);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("cannot create output directory: " + dir);
        }
        File f = new File(dir, fileName);
        boolean isNew = !f.exists();
        try (PrintWriter w = new PrintWriter(f)) {
            w.println(sgf);
        }
        if (!isNew) return; // a later round or rerun wrote the same position; keep one index row
        File index = new File(dir, "index.md");
        boolean fresh = !index.exists();
        try (PrintWriter w = new PrintWriter(new java.io.FileWriter(index, true))) {
            if (fresh) {
                w.print(header);
            }
            w.println(indexRow(pos, fileName));
        }
    }

    private String indexRow(Position pos, String fileName) {
        return "| [" + fileName + "](" + fileName + ") | " + pos.game.name + " move " + pos.moveNumber
                + " | " + pos.colorName()
                + " | " + pos.label(pos.answer) + " = " + pos.answer.move
                + " | " + pos.label(pos.mistake) + " = " + pos.mistake.move
                + " | " + String.format("%.2f", pos.strongGap()) + " / " + String.format("%.2f", pos.gap())
                + " | " + (pos.game.br == null ? "?" : pos.game.br) + " / " + (pos.game.wr == null ? "?" : pos.game.wr)
                + " | " + outDir.getName() + " |";
    }

    // the problem: full board setup, A and B labels, one move deep
    String problemSgf(Position pos) throws Exception {
        Game game = pos.game;
        Node root = new Node(null);
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                root.board.board[x][y].stone = pos.board.board[x][y].stone;
            }
        }
        root.defaultToMoveColor = pos.color;
        root.forceMove = true;
        root.addXtraTag("GM", "1");
        root.addXtraTag("FF", "4");
        root.addXtraTag("SZ", "19");
        root.addXtraTag("KM", String.valueOf(scan.komiFor(game)));
        root.addXtraTag("PL", pos.color == Intersection.BLACK ? "B" : "W");
        root.addXtraTag("AP", "autoprob fuseki prototype");
        copy(root, "PB", game.pb);
        copy(root, "BR", game.br);
        copy(root, "PW", game.pw);
        copy(root, "WR", game.wr);
        copy(root, "DT", game.dt);
        copy(root, "PC", game.pc);

        Candidate first = pos.label(pos.answer).equals("A") ? pos.answer : pos.mistake;
        Candidate second = first == pos.answer ? pos.mistake : pos.answer;
        root.addAct(new LabelAction("A", first.p.x, first.p.y));
        root.addAct(new LabelAction("B", second.p.x, second.p.y));
        if (pos.lastMove != null) {
            root.addAct(new TriangleAction(pos.lastMove.x, pos.lastMove.y));
        }
        root.addAct(new CommentAction(sgfText(rootComment(pos))));

        double gap = pos.verified ? pos.strongGap() : pos.gap();
        for (Candidate c : new Candidate[] {first, second}) {
            Node child = root.addBasicMove(c.p.x, c.p.y);
            if (c == pos.answer) {
                child.addAct(new CommentAction("Correct. This is " + points(gap) + " better than " + pos.label(pos.mistake) + "."));
                child.result = Intersection.RIGHT;
            } else {
                child.addAct(new CommentAction("This loses " + points(gap) + " compared with " + pos.label(pos.answer) + "."));
            }
        }
        // one LB property with both labels: strict sgf readers reject a repeated property
        return "(" + root.outputSGF(true).replace("]LB[", "][") + ")";
    }

    private String rootComment(Position pos) {
        StringBuilder c = new StringBuilder();
        if (scan.nearMiss(pos)) {
            c.append("NEAR MISS: strong net gap ").append(String.format("%.2f", pos.strongGap())).append(", the bar is ")
                    .append(fmt(scan.minGap())).append(".\n\n");
        } else if (!pos.accepted()) {
            c.append("REJECTED (").append(pos.status()).append("): ").append(pos.reasonText()).append("\n\n");
        }
        c.append(pos.colorName()).append(" to play. Which is better, A or B?\n\n");
        c.append("-- prototype analysis --\n");
        c.append("Source: ").append(pos.game.file).append(", move ").append(pos.moveNumber)
                .append(" (the game move was ").append(pos.gameMove).append(").\n");
        c.append("Correct: ").append(pos.label(pos.answer)).append(" = ").append(pos.answer.move)
                .append(". Mistake: ").append(pos.label(pos.mistake)).append(" = ").append(pos.mistake.move).append(", the game move.\n");
        c.append("Gap: ");
        if (pos.verified) {
            c.append(fmt(pos.strongGap())).append(" strong net, ");
        }
        c.append(fmt(pos.gap())).append(" fast net.\n");
        if (pos.verified) {
            c.append("Strong net best move: ").append(pos.strongBestMove).append(", the answer is ")
                    .append(fmt(pos.strongAnswerLoss())).append(" behind it.\n");
        }
        c.append("Good moves (fast net, within ").append(props("fuseki.good_margin", "0.5")).append(" of best): ")
                .append(scan.moveList(pos.good, pos.best.score)).append(" = ").append(ideas(pos.goodIdeas)).append(".\n");
        c.append("Calm: score ").append(scan.lead(pos, pos.scoreLead)).append(", stone ownership stdev mean ")
                .append(fmt2(pos.stdevMean)).append(" max ").append(fmt2(pos.stdevMax)).append(", lines: ")
                .append(linesText(pos)).append(".\n");
        c.append("P(pick correct over mistake) by rank: ").append(curveText(pos)).append(". Crosses 50%: ")
                .append(pos.crossover).append(".\n");
        c.append("Human policy for the game move at ").append(props("fuseki.profile_prefix", "rank_")).append(pos.moverRank)
                .append(": ").append(pct(pos.mistakeHumanPolicy)).append(" (choice #").append(pos.mistakeHumanOrder).append(").");
        return c.toString();
    }

    private void writeMarkdown(boolean fin, String status) throws Exception {
        List<Position> accepted = new ArrayList<>();
        List<Position> near = new ArrayList<>();
        List<Position> rejected = new ArrayList<>();
        List<Position> pending = new ArrayList<>();
        for (Position pos : scan.positions) {
            if (pos.accepted()) accepted.add(pos);
            else if (scan.nearMiss(pos)) near.add(pos);
            else if (pos.awaitingVerify()) pending.add(pos);
            else if (pos.stage >= FusekiScanner.STAGE_CALM) rejected.add(pos);
        }
        accepted.sort((a, b) -> Double.compare(b.strongGap(), a.strongGap()));
        near.sort((a, b) -> Double.compare(b.strongGap(), a.strongGap()));

        StringBuilder md = new StringBuilder();
        md.append("# Fuseki problem scan\n\n");
        md.append("Started ").append(scan.startedAt).append(" on `").append(props("path", "")).append("`. Status: **")
                .append(status).append("** (updated ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()))
                .append(").\n\n");
        double hours = (System.currentTimeMillis() - scan.runStart) / 3600000.0;
        md.append(String.format("Progress: %.1f hours, %d games scanned (%d of %d files examined", hours, scan.gamesScanned,
                        scan.filesExamined, scan.filesTotal))
                .append(scan.budgetHit ? ", time budget reached" : "").append(scan.stopped ? ", stopped by the stop file" : "")
                .append("), ").append(accepted.size()).append(" problems, ").append(near.size()).append(" near misses.")
                .append(" Problems and near misses from every run also collect in `").append(scan.problemsDir()).append("/` and `")
                .append(scan.nearDir()).append("/`. To end a running scan after its current round, create `")
                .append(scan.stopFile()).append("`.\n\n");
        md.append("Fast net (screen and calm check): `").append(scan.screenModelName()).append("`. Strong net (verify): `")
                .append(scan.strongModelName()).append("`.\n\n");
        md.append("Settings:\n- ").append(scan.settingsSummary()).append("\n\n");
        md.append("Scores are points from the side to play's view. The two choices are labeled A and B in board order, so the correct move can be either label.\n\n");

        appendFunnel(md, accepted, pending);
        appendGames(md);

        md.append("## Accepted problems (").append(accepted.size()).append(")\n\n");
        if (accepted.isEmpty()) {
            md.append(fin ? "None.\n\n" : "Not verified yet.\n\n");
        }
        int i = 0;
        for (Position pos : accepted) {
            appendProblem(md, ++i, pos);
        }

        if (!near.isEmpty()) {
            md.append("## Near misses: strong net gap ").append(fmt(scan.nearGapMin())).append(" to ").append(fmt(scan.minGap()))
                    .append(" (").append(near.size()).append(")\n\n");
            md.append("Passed every check except the final gap. SGFs are in `near_misses/` and also collect across runs in `")
                    .append(scan.nearDir()).append("/`.\n\n");
            i = 0;
            for (Position pos : near) {
                appendProblem(md, ++i, pos);
            }
        }

        if (!pending.isEmpty()) {
            md.append("## Awaiting the strong net (").append(pending.size()).append(")\n\n");
            appendTable(md, pending);
        }

        md.append("## Rejected at the calm check or by the strong net (").append(rejected.size()).append(")\n\n");
        md.append("Each has an SGF in `rejected/` with the reason at the top of the root comment, so you can judge the filter by eye.\n\n");
        appendTable(md, rejected);

        md.append("## Screened out\n\n");
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("corner", 0);
        counts.put("score", 0);
        counts.put("loss", 0);
        counts.put("game_done", 0);
        int screened = 0;
        for (Position pos : scan.positions) {
            if (pos.stage != FusekiScanner.STAGE_SCREEN) continue;
            screened++;
            for (Reason r : pos.reasons) counts.merge(r.code, 1, Integer::sum);
        }
        md.append(screened).append(" positions stopped at the screen (a position can have several reasons): ")
                .append(counts.get("corner")).append(" with an empty corner, ")
                .append(counts.get("score")).append(" with the score out of range, ")
                .append(counts.get("loss")).append(" where the game move lost less than ").append(fmt(scan.screenMinLoss()))
                .append(", ").append(counts.get("game_done")).append(" not checked because their game already had a candidate")
                .append(". Every position is in `positions.csv`.\n");

        try (PrintWriter w = new PrintWriter(new File(outDir, "report.md"))) {
            w.print(md);
        }
    }

    private void appendFunnel(StringBuilder md, List<Position> accepted, List<Position> pending) {
        int scanned = scan.positions.size();
        int gates = 0, lost = 0, lostBig = 0, calmChecked = 0, calmPassed = 0;
        Map<String, Integer> calmReasons = new LinkedHashMap<>();
        Map<String, Integer> strongReasons = new LinkedHashMap<>();
        for (Position pos : scan.positions) {
            if (pos.cornersOk && pos.scoreOk) {
                gates++;
                if (pos.screenLoss >= scan.screenMinLoss()) lost++;
                if (pos.screenLoss >= scan.minGap()) lostBig++;
            }
            if (pos.stage >= FusekiScanner.STAGE_CALM) calmChecked++;
            if (pos.stage >= FusekiScanner.STAGE_VERIFY) calmPassed++;
            for (Reason r : pos.reasons) {
                if (pos.stage == FusekiScanner.STAGE_CALM) calmReasons.merge(r.code, 1, Integer::sum);
                if (pos.stage == FusekiScanner.STAGE_VERIFY) strongReasons.merge(r.code, 1, Integer::sum);
            }
        }
        int gamesScanned = 0;
        for (Game g : scan.games) {
            if (g.skipped == null) gamesScanned++;
        }
        md.append("## Funnel\n\n");
        md.append("| Step | Positions |\n|---|---|\n");
        md.append("| Scanned: every move in the window of ").append(gamesScanned).append(" even games | ").append(scanned).append(" |\n");
        md.append("| All four corners taken, score in range | ").append(gates).append(" |\n");
        md.append("| ...and the game move lost >= ").append(fmt(scan.screenMinLoss())).append(" (fast screen) | ").append(lost).append(" |\n");
        md.append("| ...of which it lost >= ").append(fmt(scan.minGap())).append(" (fast screen) | ").append(lostBig).append(" |\n");
        md.append("| Calm check run | ").append(calmChecked).append(" |\n");
        md.append("| Calm check passed | ").append(calmPassed).append(" |\n");
        md.append("| Strong net confirmed | ").append(accepted.size())
                .append(pending.isEmpty() ? "" : " (" + pending.size() + " still pending)").append(" |\n\n");
        if (!calmReasons.isEmpty()) {
            md.append("Calm check rejections by reason (a position can have several): ").append(reasonCounts(calmReasons)).append(".\n\n");
        }
        if (!strongReasons.isEmpty()) {
            md.append("Strong net rejections by reason: ").append(reasonCounts(strongReasons)).append(".\n\n");
        }
    }

    private String reasonCounts(Map<String, Integer> counts) {
        Map<String, String> names = new HashMap<>();
        names.put("ideas", "too few good ideas");
        names.put("tactics", "fight in a line");
        names.put("gap", "gap too small");
        names.put("fine", "game move fine on closer look");
        names.put("distance", "no good move far enough from the game move");

        names.put("strong_gap", "strong net gap too small");
        names.put("strong_best", "strong net prefers another move");
        names.put("error", "analysis error");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(names.getOrDefault(e.getKey(), e.getKey()) + " " + e.getValue());
        }
        return String.join(", ", parts);
    }

    // scanned games get a row; skipped ones (most of a filtered collection) only a count per reason
    private void appendGames(StringBuilder md) {
        md.append("## Games\n\n");
        Map<String, Integer> skips = new LinkedHashMap<>();
        for (Game g : scan.games) {
            if (g.skipped != null && !"error".equals(g.skipCode)) {
                skips.merge(g.skipCode == null ? "other" : g.skipCode, 1, Integer::sum);
            }
        }
        if (!skips.isEmpty()) {
            Map<String, String> names = new HashMap<>();
            names.put("size", "not 19x19");
            names.put("handicap", "handicap");
            names.put("player", "excluded player (bots)");
            names.put("ranks", "ranks outside the range or missing");
            names.put("short", "too short");
            names.put("parse", "unreadable");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : skips.entrySet()) {
                parts.add(names.getOrDefault(e.getKey(), e.getKey()) + " " + e.getValue());
            }
            md.append("Skipped games: ").append(String.join(", ", parts)).append(".\n\n");
        }
        md.append("| Game | Black | White | Moves | Scanned | Lost >= ").append(fmt(scan.screenMinLoss()))
                .append(" | Calm | Accepted | Note |\n|---|---|---|---|---|---|---|---|---|\n");
        for (Game g : scan.games) {
            if (g.skipped != null && !"error".equals(g.skipCode)) continue;
            int scanned = 0, lost = 0, calm = 0, acc = 0;
            for (Position pos : scan.positions) {
                if (pos.game != g) continue;
                scanned++;
                if (pos.stage >= FusekiScanner.STAGE_CALM) lost++;
                if (pos.stage >= FusekiScanner.STAGE_VERIFY) calm++;
                if (pos.accepted()) acc++;
            }
            md.append("| ").append(g.name).append(" | ").append(player(g.pb, g.br)).append(" | ").append(player(g.pw, g.wr))
                    .append(" | ").append(g.moveCount).append(" | ").append(scanned).append(" | ").append(lost).append(" | ")
                    .append(calm).append(" | ").append(acc).append(" | ").append(g.skipped == null ? "" : "skipped: " + g.skipped)
                    .append(" |\n");
        }
        md.append("\n");
    }

    private void appendProblem(StringBuilder md, int num, Position pos) {
        String prefix = props("fuseki.profile_prefix", "rank_");
        md.append("### ").append(num).append(". ").append(pos.game.name).append(" move ").append(pos.moveNumber)
                .append(": ").append(pos.colorName()).append(" to play\n\n");
        md.append("SGF: `").append(pos.sgfPath).append("`\n\n");
        md.append("- **Correct: ").append(pos.label(pos.answer)).append(" = ").append(pos.answer.move).append("**. ")
                .append(prefix).append(props("fuseki.natural_rank", "3d")).append(" policy ").append(pct(pos.answer.naturalPolicy))
                .append("; strong net has it ").append(fmt(pos.strongAnswerLoss())).append(" behind its best move ")
                .append(pos.strongBestMove).append(".\n");
        md.append("- **Mistake: ").append(pos.label(pos.mistake)).append(" = ").append(pos.mistake.move).append("**, the game move. Loses ")
                .append(String.format("%.2f", pos.strongGap())).append(" (strong net) / ").append(fmt(pos.gap())).append(" (fast net). ")
                .append(prefix).append(pos.moverRank).append(" policy ").append(pct(pos.mistakeHumanPolicy))
                .append(", choice #").append(pos.mistakeHumanOrder).append(" at the player's rank.\n");
        md.append("- Good moves (fast net, within ").append(props("fuseki.good_margin", "0.5")).append("): ")
                .append(scan.moveList(pos.good, pos.best.score)).append(". That's ").append(ideas(pos.goodIdeas)).append(".\n");
        md.append("- Calm: score ").append(scan.lead(pos, pos.scoreLead)).append("; stone ownership stdev mean ")
                .append(fmt2(pos.stdevMean)).append(", max ").append(fmt2(pos.stdevMax)).append("; lines: ").append(linesText(pos)).append(".\n");
        md.append("- P(pick correct over mistake) by rank: ").append(curveText(pos)).append(". Crosses 50%: ").append(pos.crossover).append(".\n\n");
        Map<Point, String> labels = new HashMap<>();
        labels.put(pos.answer.p, pos.label(pos.answer));
        labels.put(pos.mistake.p, pos.label(pos.mistake));
        md.append("```\n").append(asciiBoard(pos.board, pos.lastMove, labels)).append("```\n\n");
    }

    private void appendTable(StringBuilder md, List<Position> list) {
        if (list.isEmpty()) {
            md.append("None.\n\n");
            return;
        }
        md.append("| Position | To play | Game move | Screen loss | Answer | Gap fast / strong | Ideas | Why | SGF |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Position pos : list) {
            md.append("| ").append(pos.game.name).append(" move ").append(pos.moveNumber)
                    .append(" | ").append(pos.colorName())
                    .append(" | ").append(pos.gameMove)
                    .append(" | ").append(fmt(pos.screenLoss))
                    .append(" | ").append(pos.answer == null ? "" : pos.answer.move)
                    .append(" | ").append(pos.answer == null ? "" : fmt(pos.gap()))
                    .append(pos.verified ? " / " + fmt(pos.strongGap()) : "")
                    .append(" | ").append(pos.goodIdeas)
                    .append(" | ").append(pos.reasons.isEmpty() ? "" : pos.reasonText().replace("|", "/"))
                    .append(" | ").append(pos.sgfPath == null ? "" : "`" + pos.sgfPath + "`")
                    .append(" |\n");
        }
        md.append("\n");
    }

    private void writeCsv() throws Exception {
        try (PrintWriter w = new PrintWriter(new File(outDir, "positions.csv"))) {
            w.println("game,move,to_play,game_move,status,reasons,score_lead,screen_loss,best,good_moves,good_ideas,"
                    + "answer,answer_label,answer_score,answer_natural_policy,mistake_label,mistake_score,gap_fast,"
                    + "stdev_mean,stdev_max,lines,mover_rank,mistake_human_policy,mistake_human_order,curve,crossover,"
                    + "strong_best,strong_best_score,strong_answer,strong_mistake,strong_gap,sgf,black_rank,white_rank");
            for (Position pos : scan.positions) {
                List<String> f = new ArrayList<>();
                f.add(pos.game.name);
                f.add(String.valueOf(pos.moveNumber));
                f.add(pos.color == Intersection.BLACK ? "B" : "W");
                f.add(pos.gameMove);
                f.add(scan.nearMiss(pos) ? "near miss" : pos.status());
                f.add(pos.reasonText());
                f.add(num(pos.scoreLead));
                f.add(num(pos.screenLoss));
                boolean calm = pos.best != null;
                f.add(calm ? pos.best.move : "");
                f.add(calm ? scan.moveList(pos.good, pos.best.score) : "");
                f.add(calm ? String.valueOf(pos.goodIdeas) : "");
                boolean pair = pos.answer != null;
                f.add(pair ? pos.answer.move : "");
                f.add(pair ? pos.label(pos.answer) : "");
                f.add(pair ? num(pos.answer.score) : "");
                f.add(pair ? num3(pos.answer.naturalPolicy) : "");
                f.add(pair ? pos.label(pos.mistake) : "");
                f.add(pair ? num(pos.mistake.score) : "");
                f.add(pair ? num(pos.gap()) : "");
                f.add(calm ? num2(pos.stdevMean) : "");
                f.add(calm ? num2(pos.stdevMax) : "");
                f.add(pair ? linesText(pos) : "");
                f.add(pos.moverRank == null ? "" : pos.moverRank);
                f.add(pair ? num3(pos.mistakeHumanPolicy) : "");
                f.add(pair ? String.valueOf(pos.mistakeHumanOrder) : "");
                f.add(pair ? curveText(pos) : "");
                f.add(pos.crossover);
                f.add(pos.verified ? pos.strongBestMove : "");
                f.add(pos.verified ? num(pos.strongBest) : "");
                f.add(pos.verified ? num(pos.strongAnswer) : "");
                f.add(pos.verified ? num(pos.strongMistake) : "");
                f.add(pos.verified ? num(pos.strongGap()) : "");
                f.add(pos.sgfPath == null ? "" : pos.sgfPath);
                f.add(pos.game.br == null ? "" : pos.game.br);
                f.add(pos.game.wr == null ? "" : pos.game.wr);
                List<String> quoted = new ArrayList<>();
                for (String s : f) quoted.add(csv(s));
                w.println(String.join(",", quoted));
            }
        }
    }

    public void printRound() {
        int accepted = 0, near = 0;
        for (Position pos : scan.positions) {
            if (pos.accepted()) accepted++;
            else if (scan.nearMiss(pos)) near++;
        }
        System.out.println("ROUND " + scan.round + " DONE at " + String.format("%.1f", (System.currentTimeMillis() - scan.runStart) / 3600000.0)
                + " h: " + scan.gamesScanned + " games scanned, " + accepted + " problems, " + near + " near misses");
    }

    public void printSummary() {
        System.out.println();
        System.out.println("==== FUSEKI SCAN SUMMARY ====");
        int accepted = 0;
        for (Position pos : scan.positions) {
            if (!pos.accepted()) continue;
            accepted++;
            System.out.println(pos.id() + ": " + pos.colorName() + " to play, correct " + pos.label(pos.answer) + "=" + pos.answer.move
                    + ", mistake " + pos.label(pos.mistake) + "=" + pos.mistake.move + " (game move), gap "
                    + fmt(pos.strongGap()) + " strong / " + fmt(pos.gap()) + " fast, " + ideas(pos.goodIdeas) + ", crosses 50% at "
                    + pos.crossover);
        }
        for (Position pos : scan.positions) {
            if (scan.nearMiss(pos)) {
                System.out.println("near miss " + pos.id() + ": strong gap " + String.format("%.2f", pos.strongGap())
                        + " -> " + scan.nearDir() + "/" + pos.id() + ".sgf");
            }
        }
        System.out.println("files examined: " + scan.filesExamined + " of " + scan.filesTotal
                + (scan.budgetHit ? " (time budget reached)" : ""));
        System.out.println("positions scanned: " + scan.positions.size() + ", accepted: " + accepted);
        System.out.println("report: " + new File(outDir, "report.md").getPath());
    }

    private String linesText(Position pos) {
        if (pos.best == null) return "";
        List<String> parts = new ArrayList<>();
        List<Candidate> seen = new ArrayList<>();
        for (Candidate c : new Candidate[] {pos.best, pos.answer, pos.mistake}) {
            if (c == null || seen.contains(c)) continue;
            seen.add(c);
            if (c.tactics.isTactical()) parts.add(c.move + ": " + c.tactics.describe());
        }
        return parts.isEmpty() ? "no atari or capture" : String.join("; ", parts);
    }

    private String curveText(Position pos) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Double> e : pos.curve.entrySet()) {
            parts.add(e.getKey() + " " + (e.getValue().isNaN() ? "?" : String.format("%.2f", e.getValue())));
        }
        return String.join(", ", parts);
    }

    // X/O stones, x/o the last move, + star points, letters the choices
    static String asciiBoard(Board board, Point lastMove, Map<Point, String> labels) {
        String cols = "ABCDEFGHJKLMNOPQRST";
        StringBuilder sb = new StringBuilder();
        sb.append("   ");
        for (char c : cols.toCharArray()) sb.append(' ').append(c);
        sb.append('\n');
        for (int y = 0; y < 19; y++) {
            int row = 19 - y;
            sb.append(String.format("%2d ", row));
            for (int x = 0; x < 19; x++) {
                String label = labels.get(new Point(x, y));
                boolean last = lastMove != null && lastMove.x == x && lastMove.y == y;
                int s = board.board[x][y].stone;
                char ch;
                if (label != null) ch = label.charAt(0);
                else if (s == Intersection.BLACK) ch = last ? 'x' : 'X';
                else if (s == Intersection.WHITE) ch = last ? 'o' : 'O';
                else ch = Intersection.isStarPoint(x, y, 19, 19) ? '+' : '.';
                sb.append(' ').append(ch);
            }
            sb.append(String.format(" %2d", row)).append('\n');
        }
        sb.append("   ");
        for (char c : cols.toCharArray()) sb.append(' ').append(c);
        sb.append("\n(X/O stones, x/o last move, A/B the choices)\n");
        return sb.toString();
    }

    private static String player(String name, String rank) {
        String s = name == null ? "?" : name;
        return rank == null ? s : s + " " + rank;
    }

    private static void copy(Node root, String tag, String val) {
        if (val != null) root.addXtraTag(tag, sgfText(val));
    }

    // sgf text cannot hold an unescaped ']', and the goproblems parser reads these words as markers
    private static String sgfText(String s) {
        return s.replace("\\", "/").replace("]", ")")
                .replace("RIGHT", "right").replace("WRONG", "wrong").replace("FORCE", "force")
                .replace("CHOICE", "choice").replace("NOTTHIS", "notthis");
    }

    static String ideas(int n) {
        return n + (n == 1 ? " idea" : " ideas");
    }

    private static String points(double gap) {
        long n = Math.round(gap);
        if (n < 1) return "less than a point";
        return "about " + n + (n == 1 ? " point" : " points");
    }

    private String props(String name, String def) {
        return scan.propsValue(name, def);
    }

    private static String pct(double p) {
        return Double.isNaN(p) ? "?" : String.format("%.1f%%", 100 * p);
    }

    private static String fmt2(double d) {
        return Double.isNaN(d) ? "?" : String.format("%.2f", d);
    }

    private static String num(double d) {
        return Double.isNaN(d) ? "" : String.format("%.2f", d);
    }

    private static String num2(double d) {
        return Double.isNaN(d) ? "" : String.format("%.3f", d);
    }

    private static String num3(double d) {
        return Double.isNaN(d) ? "" : String.format("%.4f", d);
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
