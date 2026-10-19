package autoprob.fuseki;

import java.awt.Point;
import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import autoprob.KataBrain;
import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.Parser;
import autoprob.katastruct.AllowMove;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import autoprob.katastruct.MoveInfo;

// scans games for whole board fuseki choice problems. a position qualifies when
// the move actually played lost points in a calm opening: every corner taken, no
// tactical fighting, and several different moves that all keep the balance. the
// problem pairs the game move (plausible because someone played it) with the most
// human-natural of the good moves, one move deep.
//
// stage 1: fast net batch-analyzes every turn in the move window, keeps game moves that lost points
// stage 2: fast net evaluates candidate moves with equal visits to check calmness and pick the answer
// stage 3: strong net (kata.model) confirms the gap between the answer and the game move
public class FusekiScanner {
    public static final int STAGE_SCREEN = 1;
    public static final int STAGE_CALM = 2;
    public static final int STAGE_VERIFY = 3;

    public static class Game {
        public String file;
        public String name;
        public String pb, pw, br, wr, dt, pc;
        public double gameKomi = Double.NaN;
        public int moveCount;
        public String skipped; // why the game was not scanned, null if scanned
        public String skipCode; // short reason kind for the report's skip counts
        List<List<String>> moves = new ArrayList<>(); // katago move list
        List<Node> moveNodes = new ArrayList<>();
        String sgfText; // the source game, cleaned, for the candidate json
    }

    public static class Candidate {
        public String move;
        public Point p;
        public String sources = ""; // k = katago top move, p = katago policy, h = human policy, g = game move
        public double score = Double.NaN; // mover's perspective, equal visits per candidate
        public List<String> pv = new ArrayList<>();
        public double naturalPolicy; // human policy at fuseki.natural_rank
        public PvTactics tactics;
    }

    public static class Reason {
        public final String code;
        public final String text;

        Reason(String code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    public static class Position {
        public Game game;
        public int turn; // moves already played
        public int moveNumber; // the move being chosen (turn + 1)
        public int color; // side to play
        public Board board; // position before the game move
        public Point lastMove; // the opponent's previous move
        public String gameMove;
        public int stage; // furthest stage reached
        public List<Reason> reasons = new ArrayList<>();
        public long millis;

        // stage 1, fast net batch
        public double scoreLead = Double.NaN; // mover's perspective at the analysis komi
        public double screenLoss = Double.NaN;
        public boolean cornersOk, scoreOk;

        // stage 2, fast net equal-visit candidates
        public List<Candidate> candidates = new ArrayList<>();
        public List<Candidate> good = new ArrayList<>();
        public Candidate best, answer, mistake;
        public int goodIdeas;
        public double stdevMean = Double.NaN, stdevMax = Double.NaN;
        public String moverRank;
        public double mistakeHumanPolicy = Double.NaN;
        public int mistakeHumanOrder;
        public LinkedHashMap<String, Double> curve = new LinkedHashMap<>(); // rank -> P(pick answer over mistake)
        public String crossover = "";

        // stage 3, strong net
        public boolean verified;
        public String strongBestMove;
        public double strongBest = Double.NaN, strongAnswer = Double.NaN, strongMistake = Double.NaN;

        public String sgfPath; // relative to the run directory

        public boolean accepted() {
            return verified && reasons.isEmpty();
        }

        public boolean awaitingVerify() {
            return stage == STAGE_VERIFY && !verified && reasons.isEmpty();
        }

        public double gap() {
            return answer.score - mistake.score;
        }

        public double strongGap() {
            return strongAnswer - strongMistake;
        }

        public double strongAnswerLoss() {
            return Math.max(strongBest, strongAnswer) - strongAnswer;
        }

        public boolean hasReason(String code) {
            for (Reason r : reasons) {
                if (r.code.equals(code)) return true;
            }
            return false;
        }

        public String reasonText() {
            List<String> l = new ArrayList<>();
            for (Reason r : reasons) l.add(r.text);
            return String.join("; ", l);
        }

        public String status() {
            if (accepted()) return "accepted";
            if (awaitingVerify()) return "awaiting strong net";
            if (hasReason("game_done")) return "not checked, game already has a candidate";
            if (stage == STAGE_SCREEN) return "screened out";
            if (stage == STAGE_CALM) return "rejected at calm check";
            return "rejected by strong net";
        }

        // choices are labeled in board order so the label never gives the answer away
        public String label(Candidate c) {
            Candidate first = answer.p.x < mistake.p.x || (answer.p.x == mistake.p.x && answer.p.y < mistake.p.y) ? answer : mistake;
            return c == first ? "A" : "B";
        }

        public String colorName() {
            return Intersection.color2name(color, true);
        }

        public String id() {
            return game.name + "_move" + moveNumber;
        }
    }

    private final Properties props;
    private final int minMove, maxMove;
    private final Double fixedKomi;
    private final int cornerSize;
    private final double maxAbsScore;
    private final String screenModel;
    private final int screenVisits;
    private final double screenMinLoss;
    private final int rootVisits, evalVisits, candidatesPerSource;
    private final double goodMargin, ideaDist;
    private final int minGoodIdeas, pvCheckMoves;
    private final int verifyRootVisits, verifyVisits;
    private final double minGap, screenMinGap, verifyAnswerMargin;
    // near misses: calm positions whose strong net gap landed in [nearGapMin, minGap), kept for review
    private final double nearGapMin;
    // problem variety: stop checking a game after this many candidates (0 = check every position), and
    // keep the two choices at least this far apart in city-block distance (0 = any)
    private final int maxCandidatesPerGame;
    private final int minChoiceDistance;
    private final String nearDir;
    private final String profilePrefix, naturalRank, defaultRank;
    private final List<String> curveRanks;
    // big collections: game filters, sampling order, and a time budget for the fast net stages
    private final String rankRange;
    private Integer rankLow, rankHigh; // both players must be inside, null = any rank
    private final List<String> excludePlayers;
    private final Long shuffleSeed;
    private final double maxMinutes;
    private final String kataConfig;
    // long runs: rounds of fast scanning then strong verification, ended by a stop file or the budget
    private final double roundMinutes;
    private final String stopFile;
    private final String problemsDir;
    // a deterministic sample of rejected candidates goes to the review queue too, so feedback can
    // show when a filter throws away good problems
    private final double reviewSampleRate;
    private final String reviewSampleDir;
    final String generatorCommit = gitCommit();
    private int queryCount = 0;
    private int restarts = 0;
    int filesTotal, filesExamined, gamesScanned, round;
    long runStart;
    boolean budgetHit, stopped;

    final List<Game> games = new ArrayList<>();
    final List<Position> positions = new ArrayList<>();
    String startedAt;
    String strongModel;

    public FusekiScanner(Properties props) {
        this.props = props;
        minMove = intProp("fuseki.min_move", 10);
        maxMove = intProp("fuseki.max_move", 50);
        String k = props.getProperty("fuseki.komi", "7.5").trim();
        fixedKomi = k.isEmpty() ? null : Double.parseDouble(k);
        cornerSize = intProp("fuseki.corner_size", 6);
        maxAbsScore = doubleProp("fuseki.max_abs_score", 8);
        screenModel = props.getProperty("fuseki.screen_model", props.getProperty("kata.model")).trim();
        screenVisits = intProp("fuseki.screen_visits", 400);
        screenMinLoss = doubleProp("fuseki.screen_min_loss", 1.5);
        rootVisits = intProp("fuseki.root_visits", 1500);
        evalVisits = intProp("fuseki.eval_visits", 600);
        candidatesPerSource = intProp("fuseki.candidates_per_source", 6);
        goodMargin = doubleProp("fuseki.good_margin", 0.5);
        ideaDist = doubleProp("fuseki.idea_dist", 2.0);
        minGoodIdeas = intProp("fuseki.min_good_ideas", 3);
        pvCheckMoves = intProp("fuseki.pv_check_moves", 6);
        verifyRootVisits = intProp("fuseki.verify_root_visits", 1000);
        verifyVisits = intProp("fuseki.verify_visits", 1600);
        minGap = doubleProp("fuseki.min_gap", 1.8);
        screenMinGap = doubleProp("fuseki.screen_min_gap", 1.3);
        verifyAnswerMargin = doubleProp("fuseki.verify_answer_margin", 1.0);
        nearGapMin = doubleProp("fuseki.near_gap_min", 1.5);
        maxCandidatesPerGame = intProp("fuseki.max_candidates_per_game", 1);
        minChoiceDistance = intProp("fuseki.min_choice_distance", 6);
        nearDir = props.getProperty("fuseki.near.dir", "fuseki_out/near_misses").trim();
        profilePrefix = props.getProperty("fuseki.profile_prefix", "rank_").trim();
        naturalRank = props.getProperty("fuseki.natural_rank", "3d").trim();
        defaultRank = props.getProperty("fuseki.default_rank", "1d").trim();
        curveRanks = Arrays.asList(props.getProperty("fuseki.curve_ranks", "20k,15k,10k,5k,1k,3d,6d,9d").trim().split("\\s*,\\s*"));
        strongModel = props.getProperty("kata.model").trim();
        rankRange = props.getProperty("fuseki.ranks", "").trim();
        if (!rankRange.isEmpty()) {
            String[] ends = rankRange.split("\\s*-\\s*");
            Integer a = ends.length == 2 ? rankValue(ends[0]) : null;
            Integer b = ends.length == 2 ? rankValue(ends[1]) : null;
            if (a == null || b == null) {
                throw new RuntimeException("fuseki.ranks must look like 15k-5k, got: " + rankRange);
            }
            rankLow = Math.min(a, b);
            rankHigh = Math.max(a, b);
        }
        excludePlayers = new ArrayList<>();
        for (String s : props.getProperty("fuseki.exclude_players", "").split(",")) {
            if (!s.trim().isEmpty()) excludePlayers.add(s.trim().toLowerCase());
        }
        String seed = props.getProperty("fuseki.shuffle_seed", "").trim();
        shuffleSeed = seed.isEmpty() ? null : Long.parseLong(seed);
        maxMinutes = doubleProp("fuseki.max_minutes", 0);
        kataConfig = props.getProperty("fuseki.kata.config", "").trim();
        roundMinutes = doubleProp("fuseki.round_minutes", 30);
        stopFile = props.getProperty("fuseki.stop_file", "fuseki_out/STOP").trim();
        problemsDir = props.getProperty("fuseki.problems.dir", "fuseki_out/problems").trim();
        reviewSampleRate = doubleProp("fuseki.review_sample_rate", 0.1);
        reviewSampleDir = props.getProperty("fuseki.review_sample.dir", "fuseki_out/review_sample").trim();
    }

    public void run() throws Exception {
        String path = props.getProperty("path");
        if (path == null) {
            throw new RuntimeException("you must pass in a path to an SGF game file or directory");
        }
        File f = new File(path);
        if (!f.exists()) {
            throw new RuntimeException("no such file or directory: " + path);
        }
        List<File> files = new ArrayList<>();
        collectSgfs(f, files);
        files.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        if (shuffleSeed != null) {
            // a time-boxed run then samples the whole collection instead of its first directory
            java.util.Collections.shuffle(files, new java.util.Random(shuffleSeed));
        }
        filesTotal = files.size();
        startedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        File outDir = new File(props.getProperty("fuseki.output.dir", "fuseki_out"),
                new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date()));
        FusekiReport report = new FusekiReport(this, outDir);
        runStart = System.currentTimeMillis();
        System.out.println(filesTotal + " candidate files under " + path);
        File stop = new File(stopFile);
        if (stop.exists() && stop.delete()) {
            System.out.println("removed a stale stop file: " + stopFile);
        }

        // rounds: fast net scanning, then strong net verification, then every output rewritten, so a
        // long run always has complete results on disk and can end at any round boundary
        java.util.Iterator<File> next = files.iterator();
        boolean more = true;
        while (more) {
            round++;
            more = scanRound(next);
            System.out.println("round " + round + " fast net stages done, " + minutes(runStart) + " in: " + gamesScanned
                    + " games scanned, " + filesExamined + " of " + filesTotal + " files examined");
            report.write(false, "round " + round + ": strong net check running");
            verifyPending();
            report.write(true, more ? "running, round " + round + " done, next round started" : "final");
            report.printRound();
        }
        report.printSummary();
    }

    // fast net stages for one round; false once the files, the time budget, or the stop file end the run
    private boolean scanRound(java.util.Iterator<File> next) throws Exception {
        long roundStart = System.currentTimeMillis();
        KataBrain brain = newBrain(screenModel);
        try {
            while (next.hasNext()) {
                if (new File(stopFile).exists()) {
                    stopped = true;
                    System.out.println("stop file found, finishing this round");
                    return false;
                }
                if (maxMinutes > 0 && (System.currentTimeMillis() - runStart) / 60000.0 >= maxMinutes) {
                    budgetHit = true;
                    System.out.println("time budget of " + fmt(maxMinutes) + " min reached, no new games");
                    return false;
                }
                if (roundMinutes > 0 && (System.currentTimeMillis() - roundStart) / 60000.0 >= roundMinutes) {
                    return true;
                }
                File file = next.next();
                filesExamined++;
                Game game = loadGame(file);
                games.add(game);
                if (game.skipped != null) continue;
                gamesScanned++;
                System.out.println();
                System.out.println("==== game " + gamesScanned + ": " + game.file + " (" + game.pb + " " + game.br + " vs "
                        + game.pw + " " + game.wr + "), " + minutes(runStart) + " in, " + filesExamined + " files examined");
                List<Position> found = null;
                try {
                    found = scanGame(brain, game);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (found == null && !brain.isAlive() && restarts < 10) {
                    restarts++;
                    System.out.println("katago died, restarting engine (restart " + restarts + ") and rescanning " + game.name);
                    brain.stopKataBrain();
                    brain = newBrain(screenModel);
                    try {
                        found = scanGame(brain, game);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                game.moveNodes = null; // the parsed tree is only needed while scanning; long runs hold many games
                if (found == null) {
                    game.skipped = "analysis failed";
                    game.skipCode = "error";
                    if (!brain.isAlive()) {
                        brain.stopKataBrain();
                        brain = newBrain(screenModel);
                    }
                    continue;
                }
                for (Position pos : found) {
                    if (pos.answer == null) pos.board = null; // boards are only kept for problem sgfs
                }
                positions.addAll(found);
            }
            return false;
        } finally {
            brain.stopKataBrain();
        }
    }

    // stage 3 on the strong net, for every candidate the fast net passed since the last round
    private void verifyPending() throws Exception {
        List<Position> toVerify = new ArrayList<>();
        for (Position pos : positions) {
            if (pos.awaitingVerify()) toVerify.add(pos);
        }
        if (toVerify.isEmpty()) return;
        KataBrain strong = newBrain(null);
        try {
            int i = 0;
            for (Position pos : toVerify) {
                i++;
                System.out.println("verify " + i + "/" + toVerify.size() + ": " + pos.id());
                try {
                    verify(strong, pos);
                } catch (Exception e) {
                    e.printStackTrace();
                    if (!strong.isAlive() && restarts < 10) {
                        restarts++;
                        System.out.println("katago died, restarting strong engine and retrying " + pos.id());
                        strong.stopKataBrain();
                        strong = newBrain(null);
                        try {
                            verify(strong, pos);
                        } catch (Exception e2) {
                            e2.printStackTrace();
                        }
                    }
                }
                if (!pos.verified && pos.reasons.isEmpty()) {
                    pos.reasons.add(new Reason("error", "strong net analysis failed"));
                }
            }
        } finally {
            strong.stopKataBrain();
        }
    }

    // .sgf files, plus extensionless ones: archives like NNGS store games without an extension
    private void collectSgfs(File f, List<File> out) {
        if (f.isFile()) {
            String n = f.getName();
            if (n.endsWith(".sgf") || (!n.contains(".") && !n.equals("zpos"))) out.add(f);
            return;
        }
        File[] listing = f.listFiles();
        if (listing == null) return;
        for (File c : listing) {
            collectSgfs(c, out);
        }
    }

    private KataBrain newBrain(String modelOverride) throws Exception {
        Properties p = props;
        if (!kataConfig.isEmpty()) {
            p = new Properties();
            p.putAll(props);
            p.setProperty("kata.config", kataConfig);
        }
        return modelOverride == null ? new KataBrain(p) : new KataBrain(p, modelOverride);
    }

    private static Game skip(Game game, String code, String text) {
        game.skipCode = code;
        game.skipped = text;
        return game;
    }

    private static String sgfProp(String sgf, String name) {
        Matcher m = Pattern.compile("(?<![A-Z])" + name + "\\[([^\\]]*)\\]").matcher(sgf);
        return m.find() ? m.group(1).trim() : null;
    }

    private Game loadGame(File file) throws Exception {
        Game game = new Game();
        game.file = file.getPath();
        game.name = file.getName().replaceAll("\\.sgf$", "");
        // invalid utf-8 (old latin-1 archives) only garbles names, never the moves
        String sgf = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        // cheap header checks before the full parse, which matters when filters skip most of a big collection
        String head = sgf.length() > 3000 ? sgf.substring(0, 3000) : sgf;
        String sz = sgfProp(head, "SZ");
        if (sz != null && !sz.equals("19")) {
            return skip(game, "size", "board size " + sz);
        }
        String ha = sgfProp(head, "HA");
        if (ha != null && !ha.isEmpty() && !ha.equals("0")) {
            return skip(game, "handicap", "handicap " + ha);
        }
        game.pb = sgfProp(head, "PB");
        game.pw = sgfProp(head, "PW");
        game.br = sgfProp(head, "BR");
        game.wr = sgfProp(head, "WR");
        for (String who : new String[] {game.pb, game.pw}) {
            if (who == null) continue;
            for (String ex : excludePlayers) {
                if (who.toLowerCase().contains(ex)) {
                    return skip(game, "player", "excluded player " + who);
                }
            }
        }
        if (rankLow != null) {
            Integer b = rankValue(game.br), w = rankValue(game.wr);
            if (b == null || w == null) {
                return skip(game, "ranks", "missing rank");
            }
            if (b < rankLow || b > rankHigh || w < rankLow || w > rankHigh) {
                return skip(game, "ranks", "ranks " + game.br + "/" + game.wr + " outside " + rankRange);
            }
        }
        Node root;
        try {
            root = new Parser().parse(sgf);
        } catch (Exception e) {
            return skip(game, "parse", "parse error: " + e.getMessage());
        }
        game.sgfText = cleanSgf(sgf);
        game.dt = root.getXtra("DT");
        game.pc = root.getXtra("PC");
        String km = root.getXtra("KM");
        if (km != null) {
            try {
                game.gameKomi = Double.parseDouble(km.trim());
            } catch (NumberFormatException e) {
                // leave unknown
            }
        }
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                if (!root.board.board[x][y].isEmpty()) {
                    return skip(game, "handicap", "setup stones");
                }
            }
        }
        // the main line, stopping at the first pass: the opening is over by then
        Node n = root;
        while (n != null) {
            MoveAction ma = n.getMoveAction();
            if (ma != null) {
                Point p = ma.loc;
                if (p.x < 0 || p.x > 18 || p.y < 0 || p.y > 18) break;
                game.moveNodes.add(n);
                game.moves.add(Arrays.asList(ma.stone == Intersection.BLACK ? "B" : "W", Intersection.toGTPloc(p.x, p.y)));
            }
            n = n.favoriteSon();
        }
        game.moveCount = game.moves.size();
        if (game.moveCount < minMove) {
            return skip(game, "short", "only " + game.moveCount + " moves");
        }
        return game;
    }

    // printable ascii and newlines only: old archives carry CRLF line ends, latin-1 names and stray
    // control bytes, none of which the problems need
    static String cleanSgf(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || (c >= 0x20 && c <= 0x7e)) sb.append(c);
            else if (c == '\t') sb.append(' ');
        }
        return sb.toString();
    }

    // a source path that means the same thing on another machine: relative to the archive it is in
    static String portablePath(String file) {
        String f = file.replace('\\', '/');
        int i = f.indexOf("/NNGS/");
        if (i >= 0) return f.substring(i + 1);
        String home = System.getProperty("user.home");
        if (home != null && f.startsWith(home)) return "~" + f.substring(home.length());
        return f;
    }

    // the scanner commit, so feedback can be tied to the rules that produced a candidate
    private static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String head = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() != 0) return "unknown";
            Process d = new ProcessBuilder("git", "status", "--porcelain", "--", "src").redirectErrorStream(true).start();
            String dirty = new String(d.getInputStream().readAllBytes()).trim();
            d.waitFor();
            return head + (dirty.isEmpty() ? "" : "-dirty");
        } catch (Exception e) {
            return "unknown";
        }
    }

    static int cityDistance(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    // rank on one scale: 1k = 0, 10k = -9, 1d = 1, pros above 9d; null when missing ("NR", "?")
    static Integer rankValue(String r) {
        if (r == null) return null;
        Matcher m = Pattern.compile("^\\s*(\\d+)\\s*([kdpKDP])").matcher(r);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group(1));
        char c = Character.toLowerCase(m.group(2).charAt(0));
        if (c == 'k') return 1 - n;
        if (c == 'd') return n;
        return 10;
    }

    // stage 1: batch screen of every turn in the window, then stage 2 for survivors
    private List<Position> scanGame(KataBrain brain, Game game) throws Exception {
        int lastMove = Math.min(maxMove, game.moveCount);
        long start = System.currentTimeMillis();
        // turn t is the position before move t+1; turn t+1 shows what the game move left
        KataQuery q = baseQuery(game, lastMove, screenVisits, null);
        q.analyzeTurns = new ArrayList<>();
        for (int t = minMove - 1; t <= lastMove; t++) {
            q.analyzeTurns.add(t);
        }
        brain.doQuery(q);
        Map<Integer, KataAnalysisResult> res = new HashMap<>();
        for (int t : q.analyzeTurns) {
            KataAnalysisResult r = brain.getResult(q.id, t);
            if (r.isError()) {
                throw new RuntimeException("screen failed at turn " + t + ": " + r.error);
            }
            res.put(t, r);
        }
        System.out.println("screened moves " + minMove + "-" + lastMove + " in " + seconds(start));

        List<Position> out = new ArrayList<>();
        // once the game has given its candidates, later positions are not calm-checked: games are
        // plentiful, and one game's problems tend to repeat one lesson
        List<Position> candidates = new ArrayList<>();
        for (int n = minMove; n <= lastMove; n++) {
            long posStart = System.currentTimeMillis();
            Position pos = newPosition(game, n - 1);
            int sign = pos.color == Intersection.BLACK ? 1 : -1;
            KataAnalysisResult before = res.get(pos.turn);
            KataAnalysisResult after = res.get(pos.turn + 1);
            pos.stage = STAGE_SCREEN;
            pos.scoreLead = sign * before.rootInfo.scoreLead;
            pos.screenLoss = sign * before.moveInfos.get(0).scoreLead - sign * after.rootInfo.scoreLead;
            String empty = emptyCorners(pos.board);
            pos.cornersOk = empty == null;
            pos.scoreOk = Math.abs(pos.scoreLead) <= maxAbsScore;
            if (!pos.cornersOk) {
                pos.reasons.add(new Reason("corner", "no stone in the " + empty + " corner"));
            }
            if (!pos.scoreOk) {
                pos.reasons.add(new Reason("score", "score " + lead(pos, pos.scoreLead) + " is outside +-" + fmt(maxAbsScore)));
            }
            if (pos.screenLoss < screenMinLoss) {
                pos.reasons.add(new Reason("loss", "game move lost only " + fmt(pos.screenLoss)));
            }
            if (pos.reasons.isEmpty() && maxCandidatesPerGame > 0 && candidates.size() >= maxCandidatesPerGame) {
                pos.reasons.add(new Reason("game_done", "not checked: the game already has a candidate (move "
                        + candidates.get(candidates.size() - 1).moveNumber + ")"));
            }
            if (pos.reasons.isEmpty()) {
                try {
                    calmCheck(brain, pos);
                } catch (Exception e) {
                    e.printStackTrace();
                    pos.reasons.add(new Reason("error", "analysis failed: " + e.getMessage()));
                    if (!brain.isAlive()) throw e;
                }
                if (pos.awaitingVerify()) {
                    candidates.add(pos);
                }
            }
            pos.millis = System.currentTimeMillis() - posStart;
            if (pos.stage >= STAGE_CALM) {
                System.out.println(describe(pos));
            }
            out.add(pos);
        }
        return out;
    }

    private Position newPosition(Game game, int turn) throws Exception {
        Position pos = new Position();
        pos.game = game;
        pos.turn = turn;
        pos.moveNumber = turn + 1;
        pos.board = turn == 0 ? new Board() : (Board) game.moveNodes.get(turn - 1).board.clone();
        pos.lastMove = turn == 0 ? null : game.moveNodes.get(turn - 1).findMove();
        pos.color = game.moveNodes.get(turn).getMoveAction().stone;
        pos.gameMove = game.moves.get(turn).get(1);
        return pos;
    }

    // stage 2: equal-visit evaluation of candidate moves from several sources
    private void calmCheck(KataBrain brain, Position pos) throws Exception {
        pos.stage = STAGE_CALM;
        Game game = pos.game;
        int sign = pos.color == Intersection.BLACK ? 1 : -1;
        pos.moverRank = normalizeRank(pos.color == Intersection.BLACK ? game.br : game.wr);

        // root search for candidates and ownership spread, human policy queries for the curve
        KataQuery rootQ = baseQuery(game, pos.turn, rootVisits, profilePrefix + naturalRank);
        rootQ.includeOwnership = true;
        rootQ.includeOwnershipStdev = true;
        brain.doQuery(rootQ);
        LinkedHashMap<String, KataQuery> humanQs = new LinkedHashMap<>();
        for (String r : curveRanks) {
            humanQs.put(r, baseQuery(game, pos.turn, 1, profilePrefix + r));
        }
        if (!humanQs.containsKey(pos.moverRank)) {
            humanQs.put(pos.moverRank, baseQuery(game, pos.turn, 1, profilePrefix + pos.moverRank));
        }
        for (KataQuery hq : humanQs.values()) {
            brain.doQuery(hq);
        }
        KataAnalysisResult root = fetch(brain, rootQ);
        if (root.isError()) {
            throw new RuntimeException("root analysis: " + root.error);
        }
        Map<String, List<Double>> human = new HashMap<>();
        for (Map.Entry<String, KataQuery> e : humanQs.entrySet()) {
            KataAnalysisResult hr = fetch(brain, e.getValue());
            if (hr.isError() || hr.humanPolicy == null) {
                System.out.println("no human policy for " + e.getKey() + ": " + hr.error);
                continue;
            }
            human.put(e.getKey(), hr.humanPolicy);
        }

        // candidates: katago's searched moves, its raw policy, the natural human policy, and the game move
        LinkedHashMap<String, Candidate> cands = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(candidatesPerSource, root.moveInfos.size()); i++) {
            addCandidate(cands, root.moveInfos.get(i).move, "k");
        }
        for (String mv : topLegal(root.policy, pos.board, candidatesPerSource)) {
            addCandidate(cands, mv, "p");
        }
        for (String mv : topLegal(root.humanPolicy, pos.board, candidatesPerSource)) {
            addCandidate(cands, mv, "h");
        }
        addCandidate(cands, pos.gameMove, "g");

        // the game move's line first: if refuting it starts a fight the position fails
        // whatever the other candidates show, so skip their evaluations
        Candidate played = cands.get(pos.gameMove);
        evaluate(brain, pos, root, Arrays.asList(played));
        if (played.tactics.isTactical()) {
            pos.mistake = played;
            pos.reasons.add(new Reason("tactics", "fight in the line after " + played.move + ": " + played.tactics.firstEvent));
            return;
        }
        List<Candidate> others = new ArrayList<>(cands.values());
        others.remove(played);
        evaluate(brain, pos, root, others);
        pos.candidates = new ArrayList<>(cands.values());
        pos.candidates.sort((x, y) -> Double.compare(y.score, x.score));
        pos.best = pos.candidates.get(0);
        pos.mistake = cands.get(pos.gameMove);
        for (Candidate c : pos.candidates) {
            if (pos.best.score - c.score <= goodMargin) pos.good.add(c);
        }
        pos.goodIdeas = countIdeas(pos.good);

        // unsettled stones show up as ownership that keeps flipping inside the search
        if (root.ownershipStdev != null) {
            double sum = 0, max = 0;
            int cnt = 0;
            for (int x = 0; x < 19; x++) {
                for (int y = 0; y < 19; y++) {
                    if (pos.board.board[x][y].isEmpty()) continue;
                    double s = root.ownershipStdev.get(x + y * 19);
                    sum += s;
                    max = Math.max(max, s);
                    cnt++;
                }
            }
            pos.stdevMean = cnt > 0 ? sum / cnt : 0;
            pos.stdevMax = max;
        }

        // the answer: the good move strong humans like most, far enough from the game move that the
        // two choices are different ideas rather than two versions of one local move
        boolean otherGood = false;
        for (Candidate c : pos.good) {
            if (c == pos.mistake) continue;
            otherGood = true;
            if (cityDistance(c.p, pos.mistake.p) < minChoiceDistance) continue;
            if (pos.answer == null || c.naturalPolicy > pos.answer.naturalPolicy) pos.answer = c;
        }
        if (pos.answer == null) {
            if (otherGood) {
                pos.reasons.add(new Reason("distance", "no good move at least " + minChoiceDistance
                        + " from the game move (" + moveList(pos.good, pos.best.score) + ")"));
            } else {
                pos.reasons.add(new Reason("fine", "game move is the best move on closer look"));
            }
            return;
        }

        List<Double> moverPolicy = human.get(pos.moverRank);
        if (moverPolicy != null) {
            pos.mistakeHumanPolicy = policyAt(moverPolicy, pos.mistake.p);
            int order = 1;
            for (int i = 0; i < 361; i++) {
                if (moverPolicy.get(i) > pos.mistakeHumanPolicy) order++;
            }
            pos.mistakeHumanOrder = order;
        }
        int firstAbove = -1;
        int idx = 0;
        for (String r : curveRanks) {
            List<Double> hp = human.get(r);
            double p = Double.NaN;
            if (hp != null) {
                double pa = policyAt(hp, pos.answer.p), pb = policyAt(hp, pos.mistake.p);
                if (pa + pb > 0) p = pa / (pa + pb);
            }
            pos.curve.put(r, p);
            if (firstAbove < 0 && p >= 0.5) firstAbove = idx;
            idx++;
        }
        if (firstAbove < 0) {
            pos.crossover = "never: every rank prefers the mistake";
        } else if (firstAbove == 0) {
            pos.crossover = "already at " + curveRanks.get(0);
        } else {
            pos.crossover = curveRanks.get(firstAbove);
        }

        if (pos.good.contains(pos.mistake)) {
            pos.reasons.add(new Reason("fine", "game move is within " + fmt(goodMargin) + " of the best on closer look"));
        } else if (pos.gap() < screenMinGap) {
            pos.reasons.add(new Reason("gap", "answer beats the game move by only " + fmt(pos.gap())));
        }
        if (pos.goodIdeas < minGoodIdeas) {
            pos.reasons.add(new Reason("ideas", "only " + pos.goodIdeas + " good idea" + (pos.goodIdeas == 1 ? "" : "s")
                    + " (" + moveList(pos.good, pos.best.score) + ")"));
        }
        List<Candidate> lines = new ArrayList<>();
        for (Candidate c : Arrays.asList(pos.best, pos.answer, pos.mistake)) {
            if (!lines.contains(c)) lines.add(c);
        }
        for (Candidate c : lines) {
            if (c.tactics.isTactical()) {
                pos.reasons.add(new Reason("tactics", "fight in the line after " + c.move + ": " + c.tactics.firstEvent));
            }
        }
        if (pos.reasons.isEmpty()) {
            pos.stage = STAGE_VERIFY;
        }
    }

    // equal-visit evaluation of each candidate: score, principal variation, and its tactics
    private void evaluate(KataBrain brain, Position pos, KataAnalysisResult root, List<Candidate> cands) throws Exception {
        int sign = pos.color == Intersection.BLACK ? 1 : -1;
        List<KataQuery> evalQs = new ArrayList<>();
        for (Candidate c : cands) {
            KataQuery eq = evalQuery(pos, c.move, evalVisits);
            brain.doQuery(eq);
            evalQs.add(eq);
        }
        int qi = 0;
        for (Candidate c : cands) {
            KataAnalysisResult er = fetch(brain, evalQs.get(qi++));
            if (er.isError()) {
                throw new RuntimeException("eval of " + c.move + ": " + er.error);
            }
            MoveInfo mi = moveInfo(er, c.move);
            c.score = sign * mi.scoreLead;
            c.pv = mi.pv == null ? new ArrayList<>() : mi.pv;
            c.naturalPolicy = policyAt(root.humanPolicy, c.p);
            c.tactics = PvTactics.replay(pos.board, pos.color, c.pv, pvCheckMoves);
        }
    }

    // stage 3: the strong net must agree the answer is near its best and well ahead of the game move
    private void verify(KataBrain brain, Position pos) throws Exception {
        int sign = pos.color == Intersection.BLACK ? 1 : -1;
        KataQuery rootQ = baseQuery(pos.game, pos.turn, verifyRootVisits, null);
        KataQuery answerQ = evalQuery(pos, pos.answer.move, verifyVisits);
        KataQuery mistakeQ = evalQuery(pos, pos.mistake.move, verifyVisits);
        brain.doQuery(rootQ);
        brain.doQuery(answerQ);
        brain.doQuery(mistakeQ);
        KataAnalysisResult root = fetch(brain, rootQ);
        KataAnalysisResult ar = fetch(brain, answerQ);
        KataAnalysisResult mr = fetch(brain, mistakeQ);
        if (root.isError() || ar.isError() || mr.isError()) {
            throw new RuntimeException("strong net analysis failed for " + pos.id());
        }
        pos.strongBestMove = root.moveInfos.get(0).move;
        pos.strongBest = sign * root.moveInfos.get(0).scoreLead;
        pos.strongAnswer = sign * moveInfo(ar, pos.answer.move).scoreLead;
        pos.strongMistake = sign * moveInfo(mr, pos.mistake.move).scoreLead;
        pos.verified = true;
        if (pos.strongGap() < minGap) {
            pos.reasons.add(new Reason("strong_gap", "strong net gap only " + fmt(pos.strongGap())));
        }
        if (pos.strongAnswerLoss() > verifyAnswerMargin) {
            pos.reasons.add(new Reason("strong_best", "strong net puts " + pos.answer.move + " "
                    + fmt(pos.strongAnswerLoss()) + " behind " + pos.strongBestMove));
        }
        System.out.println("  strong net: answer " + pos.answer.move + " " + fmt(pos.strongAnswer)
                + ", game move " + pos.mistake.move + " " + fmt(pos.strongMistake)
                + ", gap " + fmt(pos.strongGap()) + " (fast " + fmt(pos.gap()) + "), best " + pos.strongBestMove
                + " -> " + pos.status());
    }

    private KataQuery baseQuery(Game game, int turn, int visits, String humanProfile) {
        KataQuery q = new KataQuery();
        q.id = "fz" + (++queryCount) + "_"; // trailing separator: the brain keys results by id + turn
        q.initialStones = new ArrayList<>();
        q.moves = new ArrayList<>(game.moves.subList(0, turn));
        q.initialPlayer = "B";
        q.komi = komiFor(game);
        q.analyzeTurns = new ArrayList<>();
        q.analyzeTurns.add(turn);
        q.maxVisits = visits;
        q.includeOwnership = false;
        q.includeOwnershipStdev = false;
        q.includeMovesOwnership = false;
        q.includePolicy = humanProfile != null;
        if (humanProfile != null) {
            KataQuery.OverrideSettings os = new KataQuery.OverrideSettings();
            os.humanSLProfile = humanProfile;
            os.rootNumSymmetriesToSample = 8; // steadier policy for the curve
            q.setOverrideSettings(os);
        }
        return q;
    }

    private KataQuery evalQuery(Position pos, String move, int visits) {
        KataQuery q = baseQuery(pos.game, pos.turn, visits, null);
        AllowMove am = new AllowMove();
        am.player = Intersection.color2katagoname(pos.color);
        am.moves = new ArrayList<>();
        am.moves.add(move);
        am.untilDepth = 1;
        q.allowMoves = new ArrayList<>();
        q.allowMoves.add(am);
        return q;
    }

    // collects a submitted query's result, retrying once on a transient engine error
    private KataAnalysisResult fetch(KataBrain brain, KataQuery q) throws Exception {
        int turn = q.analyzeTurns.get(0);
        KataAnalysisResult r = brain.getResult(q.id, turn);
        if (r.isError() && brain.isAlive()) {
            System.out.println("analysis error (" + r.error + "), retrying once");
            q.id = q.id + "r_";
            brain.doQuery(q);
            r = brain.getResult(q.id, turn);
        }
        return r;
    }

    private static MoveInfo moveInfo(KataAnalysisResult r, String move) {
        MoveInfo mi = r.getMoveInfo(move);
        if (mi == null) {
            if (r.moveInfos == null || r.moveInfos.isEmpty()) {
                throw new RuntimeException("no move infos for " + move);
            }
            mi = r.moveInfos.get(0);
        }
        return mi;
    }

    private static void addCandidate(Map<String, Candidate> cands, String move, String source) {
        if (move == null || move.equalsIgnoreCase("pass")) return;
        Candidate c = cands.get(move);
        if (c == null) {
            c = new Candidate();
            c.move = move;
            c.p = Intersection.gtp2point(move);
            cands.put(move, c);
        }
        if (!c.sources.contains(source)) c.sources += source;
    }

    private static List<String> topLegal(List<Double> policy, Board board, int num) {
        List<String> out = new ArrayList<>();
        if (policy == null) return out;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < 361; i++) idx.add(i);
        idx.sort((a, b) -> Double.compare(policy.get(b), policy.get(a)));
        for (int i : idx) {
            if (out.size() >= num) break;
            int x = i % 19, y = i / 19;
            if (policy.get(i) <= 0 || !board.board[x][y].isEmpty()) continue;
            out.add(Intersection.toGTPloc(x, y));
        }
        return out;
    }

    private static double policyAt(List<Double> policy, Point p) {
        if (policy == null) return 0;
        return Math.max(0, policy.get(p.x + p.y * 19));
    }

    // good moves near an already counted good move are the same idea, e.g. two extension lengths
    private int countIdeas(List<Candidate> good) {
        List<Point> reps = new ArrayList<>();
        for (Candidate c : good) {
            boolean near = false;
            for (Point r : reps) {
                if (r.distance(c.p) <= ideaDist + 1e-9) near = true;
            }
            if (!near) reps.add(c.p);
        }
        return reps.size();
    }

    private String emptyCorners(Board b) {
        String[] names = {"upper left", "upper right", "lower left", "lower right"};
        int k = cornerSize;
        int[][] ranges = {{0, k, 0, k}, {19 - k, 19, 0, k}, {0, k, 19 - k, 19}, {19 - k, 19, 19 - k, 19}};
        List<String> empty = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            boolean found = false;
            for (int x = ranges[c][0]; x < ranges[c][1]; x++) {
                for (int y = ranges[c][2]; y < ranges[c][3]; y++) {
                    if (!b.board[x][y].isEmpty()) found = true;
                }
            }
            if (!found) empty.add(names[c]);
        }
        return empty.isEmpty() ? null : String.join(" and ", empty);
    }

    // KGS style ranks like "4k*" or "3d?" -> "4k"; missing or odd ranks use fuseki.default_rank
    String normalizeRank(String r) {
        if (r == null) return defaultRank;
        Matcher m = Pattern.compile("(\\d+)\\s*([kdpKDP])").matcher(r);
        if (!m.find()) return defaultRank;
        int n = Integer.parseInt(m.group(1));
        char c = Character.toLowerCase(m.group(2).charAt(0));
        if (c == 'p') return "9d";
        if (c == 'k') return Math.min(Math.max(n, 1), 20) + "k";
        return Math.min(Math.max(n, 1), 9) + "d";
    }

    String moveList(List<Candidate> l, double ref) {
        List<String> parts = new ArrayList<>();
        for (Candidate c : l) {
            parts.add(c.move + " " + signed(c.score - ref));
        }
        return String.join(", ", parts);
    }

    private String describe(Position pos) {
        StringBuilder sb = new StringBuilder();
        sb.append("  move ").append(pos.moveNumber).append(" ").append(pos.color == Intersection.BLACK ? "B" : "W")
                .append(" game ").append(pos.gameMove).append(" screen loss ").append(fmt(pos.screenLoss));
        if (pos.best != null) {
            sb.append(", best ").append(pos.best.move).append(", good ideas ").append(pos.goodIdeas);
        }
        if (pos.answer != null) {
            sb.append(", answer ").append(pos.answer.move).append(" gap ").append(fmt(pos.gap()));
        }
        sb.append(" -> ").append(pos.reasons.isEmpty() ? "to strong net" : pos.reasonText());
        sb.append(" (").append(pos.millis / 1000).append("s)");
        return sb.toString();
    }

    String lead(Position pos, double moverLead) {
        double black = pos.color == Intersection.BLACK ? moverLead : -moverLead;
        return (black >= 0 ? "B+" : "W+") + fmt(Math.abs(black));
    }

    String settingsSummary() {
        return "moves " + minMove + "-" + maxMove + ", komi " + (fixedKomi != null ? fmt(fixedKomi) : "from game")
                + ", corners within " + cornerSize + " lines, |score| <= " + fmt(maxAbsScore)
                + "\n- screen: fast net " + screenVisits + " visits per turn, game move loss >= " + fmt(screenMinLoss)
                + "\n- calm check: fast net " + rootVisits + " root visits + " + evalVisits + " per candidate (top "
                + candidatesPerSource + " per source); good = within " + fmt(goodMargin) + " of best; ideas >= " + minGoodIdeas
                + " (moves within " + fmt(ideaDist) + " are one idea); no atari/capture in the first " + pvCheckMoves
                + " moves of the best, answer, and game-move lines; gap >= " + fmt(screenMinGap)
                + "\n- answer: the good move with the highest " + profilePrefix + naturalRank + " human policy"
                + (minChoiceDistance > 0 ? " among those at least " + minChoiceDistance + " (city-block) from the game move" : "")
                + (maxCandidatesPerGame > 0 ? "; a game stops being checked after " + maxCandidatesPerGame + " candidate"
                        + (maxCandidatesPerGame == 1 ? "" : "s") : "")
                + "\n- strong net: " + verifyVisits + " visits each for answer and game move, gap >= " + fmt(minGap)
                + ", answer within " + fmt(verifyAnswerMargin) + " of the strong net's best (" + verifyRootVisits + " root visits)"
                + "\n- games: even 19x19" + (rankRange.isEmpty() ? "" : ", both players " + rankRange)
                + (excludePlayers.isEmpty() ? "" : ", excluding players matching " + String.join("/", excludePlayers))
                + (shuffleSeed != null ? ", shuffled (seed " + shuffleSeed + ")" : "")
                + (maxMinutes > 0 ? ", no new games after " + fmt(maxMinutes) + " min" : "")
                + (kataConfig.isEmpty() ? "" : ", engine config " + kataConfig);
    }

    String rankRange() {
        return rankRange;
    }

    double komiFor(Game game) {
        return fixedKomi != null ? fixedKomi : (Double.isNaN(game.gameKomi) ? 7.5 : game.gameKomi);
    }

    String propsValue(String name, String def) {
        return props.getProperty(name, def).trim();
    }

    String screenModelName() {
        return new File(screenModel).getName();
    }

    String strongModelName() {
        return new File(strongModel).getName();
    }

    List<String> curveRanks() {
        return curveRanks;
    }

    // passed every check except the strong net gap, which fell just short
    boolean nearMiss(Position pos) {
        return pos.verified && !pos.accepted() && pos.strongGap() >= nearGapMin && pos.strongGap() < minGap;
    }

    double nearGapMin() {
        return nearGapMin;
    }

    String nearDir() {
        return nearDir;
    }

    String reviewSampleDir() {
        return reviewSampleDir;
    }

    // same rule as tools/fuseki_backfill.py: the crc32 of the id decides, so reruns pick the same sample
    // (String.hashCode barely changes with the move number, which would sample runs of one game's moves)
    boolean inReviewSample(Position pos) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(pos.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue() % 1000 < reviewSampleRate * 1000;
    }

    // every fuseki.* setting plus the nets, as run
    Map<String, String> settings() {
        Map<String, String> m = new java.util.TreeMap<>();
        for (String k : props.stringPropertyNames()) {
            if (k.startsWith("fuseki.")) m.put(k, props.getProperty(k).trim());
        }
        m.put("kata.model", strongModelName());
        m.put("fuseki.screen_model", screenModelName());
        return m;
    }

    String problemsDir() {
        return problemsDir;
    }

    String stopFile() {
        return stopFile;
    }

    double minGap() {
        return minGap;
    }

    double screenMinLoss() {
        return screenMinLoss;
    }

    static String fmt(double d) {
        return String.format("%.1f", d);
    }

    static String signed(double d) {
        return (d >= 0 ? "+" : "") + String.format("%.1f", d);
    }

    private static String seconds(long start) {
        return ((System.currentTimeMillis() - start) / 1000) + "s";
    }

    private static String minutes(long start) {
        return String.format("%.1f min", (System.currentTimeMillis() - start) / 60000.0);
    }

    private int intProp(String name, int def) {
        return Integer.parseInt(props.getProperty(name, String.valueOf(def)).trim());
    }

    private double doubleProp(String name, double def) {
        return Double.parseDouble(props.getProperty(name, String.valueOf(def)).trim());
    }
}
