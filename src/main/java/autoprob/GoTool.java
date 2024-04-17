package autoprob;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.StoneGroupLogic;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import autoprob.katastruct.MoveInfo;

import java.awt.Point;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

public class GoTool {
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static final DecimalFormat dfp = new DecimalFormat("0.000");

    private void runTool(Properties props) throws Exception {
        String command = props.getProperty("cmd");
        if (command == null) {
            throw new RuntimeException("you must pass in a cmd");
        }
        if (command.equals("extents")) {
            runExtentsCommand(props);
        }
        else if (command.equals("fortress")) {
            runFortressCommand(props);
        } else if (command.equals("showpolicy")) {
            runShowPolicyCommand(props);
        } else if (command.equals("writepolicy")) {
            runWritePolicyCommand(props);
        } else if (command.equals("solve")) {
            runSolveCommand(props);
        } else {
            throw new RuntimeException("unknown command: " + command);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("go tool start...");

        Properties props = ExecBase.getRunConfig(args);

        GoTool gt = new GoTool();
        try {
            gt.runTool(props);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Node loadPassedSgf(Properties props) throws Exception {
        if (props.containsKey("csv")) {
            return loadPassedCsv(props);
        }
        String path = props.getProperty("path");
        if (path == null) {
            throw new RuntimeException("you must pass in a path");
        }
        File f = new File(path);
        if (!f.exists()) {
            throw new RuntimeException("no such file or directory: " + path);
        }
        String sgf;
        sgf = Files.readString(Path.of(path));

        // load SGF
        var parser = new Parser();
        Node node;
        node = parser.parse(sgf);

        return node;
    }

    private Node loadPassedCsv(Properties props) throws Exception {
        String path = props.getProperty("csv");
        try (Scanner scanner = new Scanner(new File(path))) {
            while (scanner.hasNextLine()) {
                String s = scanner.nextLine();
                if (s.startsWith("id")) {
                    continue; // header
                }
                return csv2node(s);
            }
        }
        throw new FileNotFoundException("no node found in csv");
    }

    // sample:
    // id,elo,size,board,sol
    //22976,713,19,0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000222000000000000000020020000000000000021221200000000000002112122000000000000210111200000000000021002120000000,7:18
    private Node csv2node(String s) {
        String[] parts = s.split(",");
        if (parts.length < 5) {
            throw new RuntimeException("bad csv line: " + s);
        }
        Node node = new Node(null);
        String board = parts[3];
        int idx = 0;
        for (int y = 0; y < node.board.boardX; y++) {
            for (int x = 0; x < node.board.boardX; x++) {
                char c = board.charAt(idx++);
                if (c == '0') {
                    node.board.board[x][y].stone = Intersection.EMPTY;
                } else if (c == '1') {
                    node.board.board[x][y].stone = Intersection.WHITE;
                } else if (c == '2') {
                    node.board.board[x][y].stone = Intersection.BLACK;
                } else {
                    throw new RuntimeException("bad char in csv: " + c);
                }
            }
        }
        // add solution paths -- we assume black to move
        // they look like "7:18;2:4"
        String[] sols = parts[4].split(";");
        for (String sol : sols) {
            String[] mv = sol.split(":");
            int x = Integer.parseInt(mv[0]);
            int y = Integer.parseInt(mv[1]);
            Node child = new Node(node);
            child.addAct(new MoveAction(Intersection.BLACK, child, x, y));
            child.result = Intersection.RIGHT;
            node.addChild(child);
        }
        return node;
    }

    private void runFortressCommand(Properties props) throws Exception {
        runExtentsCommand(props);
        System.out.println();

        Node node = loadPassedSgf(props);
        createFortress(props, node);
        System.out.println(node.board);
    }

    private static void createFortress(Properties props, Node node) {
        StoneGroupLogic sgl = new StoneGroupLogic();

        // build fortress
        int gap = 4;
        if (props.containsKey("gap")) {
            gap = Integer.parseInt(props.getProperty("gap"));
        }
        sgl.buildFortress(node.board, gap);
    }

    private void runShowPolicyCommand(Properties props) throws Exception {
        Node node = loadPassedCsv(props);
        System.out.println("(" + node.outputSGF(true) + ")");

        createFortress(props, node);

        // use katago to generate policy
        KataBrain brain = new KataBrain(props);

        QueryBuilder qb = new QueryBuilder();
        KataQuery query = qb.buildQuery(node);
        query.id = "auto:x";
        query.maxVisits = 1;
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        brain.doQuery(query); // kick off katago

        KataAnalysisResult kres = brain.getResult(query.id, 0);
        System.out.println("parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead) + ", ");

        kres.drawPolicy(node);

        // get top policy from result
        var solMoves = getSolutionMoves(node);
        var topSolPolicy = kres.getTopPolicy(5, solMoves, true);
        for (KataAnalysisResult.Policy p : topSolPolicy) {
            System.out.println("solution policy: " + df.format(p.policy) + " at " + p.x + "," + p.y);
        }
        var topMistakePolicy = kres.getTopPolicy(5, solMoves, false);
        for (KataAnalysisResult.Policy p : topMistakePolicy) {
            System.out.println("mistake policy: " + df.format(p.policy) + " at " + p.x + "," + p.y);
        }
    }

    private void runWritePolicyCommand(Properties props) throws Exception {
        String outPathString = props.getProperty("csvout.path");
        PrintWriter writer = new PrintWriter(outPathString);

        String path = props.getProperty("csv");
        Scanner scanner = new Scanner(new File(path));
        // csv header
        String headerString = scanner.nextLine();
        String[] hdr = headerString.split(",");

        // write header
        String solHdr = props.getProperty("policy.sol_hdr", "solpolicy");
        String misHdr = props.getProperty("policy.mis_hdr", "mistakepolicy");
        writer.println(headerString + "," + solHdr + "," + misHdr);

        KataBrain brain = new KataBrain(props);
        QueryBuilder qb = new QueryBuilder();

        int numPolicies = Integer.parseInt(props.getProperty("policy.max_count", "5"));
        boolean simplePolicy = Boolean.parseBoolean(props.getProperty("policy.simple", "false"));

        // read all items until finished
        while (scanner.hasNextLine()) {
            String s = scanner.nextLine();
            String id = s.split(",")[0];
            writer.print(s); // start by copying existing
            writer.print(",");
            Node node = csv2node(s);
            createFortress(props, node);

            KataQuery query = qb.buildQuery(node);
            query.id = "auto:" + id;
            query.maxVisits = 1;
            query.includePolicy = true;
            query.analyzeTurns.clear();
            query.analyzeTurns.add(0);
            brain.doQuery(query); // kick off katago

            KataAnalysisResult kres = brain.getResult(query.id, 0);
            System.out.println();
            System.out.println("=> parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead) + ", elo: " + s.split(",")[1]);

            kres.drawPolicy(node);
            // get top policy from result
            var solMoves = getSolutionMoves(node);
            var topSolPolicy = kres.getTopPolicy(numPolicies, solMoves, true);
            for (KataAnalysisResult.Policy p : topSolPolicy) {
                System.out.println("solution policy: " + df.format(p.policy) + " at " + p.x + "," + p.y);
            }
            writer.print(policy2string(topSolPolicy, simplePolicy));
            writer.print(",");

            var topMistakePolicy = kres.getTopPolicy(numPolicies, solMoves, false);
            for (KataAnalysisResult.Policy p : topMistakePolicy) {
                System.out.println("mistake policy: " + df.format(p.policy) + " at " + p.x + "," + p.y);
            }
            writer.print(policy2string(topMistakePolicy, simplePolicy));

            writer.println();
        }

        writer.flush();
        writer.close();
        System.out.println("complete to " + outPathString);
    }

    private void runSolveCommand(Properties props) throws Exception {
        String outPathString = props.getProperty("csvout.path");
        PrintWriter writer = new PrintWriter(outPathString);

        // choose a mode depending on properties
        String sgfPath = props.getProperty("path");
        if (sgfPath == null) {
            throw new RuntimeException("you must pass in a path");
        }
        File f = new File(sgfPath);
        System.out.println("reading from: " + sgfPath + ", is file: " + f.isFile());

        KataBrain brain = new KataBrain(props);

        // write CSV header
        writer.println("file,weights,visits,correct,solved,moves");

        solveSgfFile(props, sgfPath, brain, writer);

        writer.flush();
        writer.close();
        System.out.println("complete to " + outPathString);
    }

    private static void solveSgfFile(Properties props, String sgfPath, KataBrain brain, PrintWriter writer) throws Exception {
        // get just the name of the file
        writer.print(Path.of(sgfPath).getFileName() + ",");
        // get sgf
        String sgf = Files.readString(Path.of(sgfPath));
        var parser = new Parser();
        Node root = parser.parse(sgf);

        Node node = root;
        int correctCount = 0;
        String moveSequence = "";
        boolean solved = false;
        int visits = Integer.parseInt(props.getProperty("search.visits"));

        // keep playing as long as we're on the correct path
        while (true) {
            QueryBuilder qb = new QueryBuilder();

            createFortress(props, node);

            KataQuery query = qb.buildQuery(node);
            query.id = "auto:sgf";
            query.maxVisits = visits;
            query.includePolicy = true;
            query.analyzeTurns.clear();
            query.analyzeTurns.add(0);
            brain.doQuery(query); // kick off katago

            KataAnalysisResult kres = brain.getResult(query.id, 0);
            System.out.println();
            System.out.println("=> parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead));

            kres.drawPolicy(node);

            // top move from result is engine choice
            System.out.println(kres.printMoves(3));
            MoveInfo topMove = kres.moveInfos.get(0);
            moveSequence = moveSequence + topMove.move + " ";

            // figure out if this is a correct move or not
            Point p = Intersection.gtp2point(topMove.move);
            if (node.hasMove(p)) {
                Node n = node.getChildWithMove(p);
                if (n.searchForTheTruth()) {
                    correctCount++;
                    System.out.println("correct move: " + topMove.move);
                    // since we got the correct move, we can move on
                    if (n.babies.isEmpty()) {
                        System.out.println("solved!"); // end of sequence
                        solved = true;
                        break;
                    }
                    // do a computer response
                    n = n.chooseResponse();
                    moveSequence = moveSequence + n.getMoveAction() + " ";
                    // it's possible this is the end of the sequence, altho unlikely
                    if (n.babies.isEmpty()) {
                        System.out.println("solved on response!"); // end of sequence
                        solved = true;
                        break;
                    }
                    node = n; // advance
                } else {
                    System.out.println("incorrect move: " + topMove.move);
                    break;
                }
            } else {
                System.out.println("off path move: " + topMove.move);
                break;
            }
        }

        // record results in csv
        writer.print(weightsName(brain.modelPath));
        writer.print(",");
        writer.print(visits);
        writer.print(",");
        writer.print(correctCount);
        writer.print(",");
        writer.print(solved ? "solved" : "failed");
        writer.print(",");
        writer.print(moveSequence);

        System.out.println("final sequence: " + moveSequence);
    }

    // human readable version of weights
    // start from eg: /home/jeff/Downloads/katago-weights/g170-b20c256x2-s5303129600-d1228401921.bin.gz
    private static String weightsName(String modelPath) {
        String[] parts = modelPath.split("-");
        String last = parts[parts.length - 1];
        String[] lastParts = last.split("\\.");
        return lastParts[0];
    }

    // format like 7:18:0.356;2:4:0.122
    private String policy2string(List<KataAnalysisResult.Policy> policies, boolean simplePolicy) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < policies.size(); i++) {
            KataAnalysisResult.Policy p = policies.get(i);
            if (i != 0) {
                sb.append(";");
            }
            if (!simplePolicy) {
                sb.append(p.x + ":" + p.y + ":");
            }
            sb.append(dfp.format(p.policy));
        }
        return sb.toString();
    }

    private List<Point> getSolutionMoves(Node node) {
        List<Point> moves = new ArrayList<>();
        for (Node child : node.babies) {
            if (child.result == Intersection.RIGHT) {
                moves.add(child.getMoveAction().loc);
            }
        }
        return moves;
    }

    private void runExtentsCommand(Properties props) throws Exception {
        Node node = loadPassedSgf(props);
        System.out.println(node.board);
        StoneGroupLogic sgl = new StoneGroupLogic();
        int[] extents = sgl.calcGroupExtents(node.board);
        // print out the extents
        System.out.println("extents: right:" + extents[0] + " top:" + extents[1] + " left:" + extents[2] + " bottom:" + extents[3]);
        // print stone color
        System.out.println("stone color: " + (extents[4] == Intersection.BLACK ? "black" : "white"));
    }
}
