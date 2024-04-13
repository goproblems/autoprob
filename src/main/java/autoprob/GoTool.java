package autoprob;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.StoneGroupLogic;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;

import java.awt.Point;
import java.io.File;
import java.io.FileNotFoundException;
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
        writer.println(headerString + ",solpolicy,mistakepolicy");

        KataBrain brain = new KataBrain(props);
        QueryBuilder qb = new QueryBuilder();

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
            var topSolPolicy = kres.getTopPolicy(5, solMoves, true);
            for (KataAnalysisResult.Policy p : topSolPolicy) {
                System.out.println("solution policy: " + df.format(p.policy) + " at " + p.x + "," + p.y);
            }
            writer.print(policy2string(topSolPolicy));
            writer.print(",");

            var topMistakePolicy = kres.getTopPolicy(5, solMoves, false);
            for (KataAnalysisResult.Policy p : topMistakePolicy) {
                System.out.println("mistake policy: " + df.format(p.policy) + " at " + p.x + "," + p.y);
            }
            writer.print(policy2string(topMistakePolicy));

            writer.println();
        }

        writer.flush();
        writer.close();
        System.out.println("complete to " + outPathString);
    }

    // format like 7:18;2:4
    private String policy2string(List<KataAnalysisResult.Policy> policies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < policies.size(); i++) {
            KataAnalysisResult.Policy p = policies.get(i);
            if (i != 0) {
                sb.append(";");
            }
            sb.append(p.x + ":" + p.y + ":" + dfp.format(p.policy));
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
