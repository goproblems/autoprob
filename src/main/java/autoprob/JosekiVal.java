package autoprob;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.joseki.JNodeVal;
import autoprob.katastruct.AllowMove;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import autoprob.katastruct.MoveInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Properties;

public class JosekiVal {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    public static void main(String[] args) throws Exception {
        System.out.println("josekival start...");

        Properties props = ExecBase.getRunConfig(args);

        JosekiVal gt = new JosekiVal();
        try {
            gt.runTool(props);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void runTool(Properties props) throws Exception {
        String command = props.getProperty("cmd");
        if (command == null) {
            throw new RuntimeException("you must pass in a cmd");
        }
        if (command.equals("singlepos")) {
            runSinglePosCommand(props);
        } else {
            throw new RuntimeException("unknown command: " + command);
        }
    }

    private Node loadBasePosition(Properties props) throws Exception {
        String sgfPath = props.getProperty("joseki.base_sgf");
        if (sgfPath == null) {
            // make empty base node
            return new Node(null);
        }

        // read file contents
        String sgf = Files.readString(Path.of(sgfPath));

        // load SGF
        var parser = new Parser();
        Node node;
        node = parser.parse(sgf);
        node = node.advance2end();

        System.out.println("base position: ");
        System.out.println(node.board);
        return node;
    }

    // return only the followups that have good enough score, in the right corner
    private ArrayList<MoveInfo> getGoodFollowups(Properties props, KataAnalysisResult kres) {
        var moves = new ArrayList<MoveInfo>();
        int minVisits = 5;
        for (MoveInfo mi: kres.moveInfos) {
            if (mi.visits < minVisits) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("kar move: ").append(mi.move).append(", visits: ").append(mi.visits).append(", score: ").
                    append(df.format(mi.scoreLead)).append(", policy: ").append(df.format(mi.prior * 1000.0)).
                    append(", order: ").append(mi.order).append(", weight: ").append(mi.weight);

            System.out.println(sb);
        }
        return moves;
    }

    private Node runSinglePosCommand(Properties props) throws Exception {
        String path = props.getProperty("path");
        if (path == null) {
            throw new RuntimeException("you must pass in a path");
        }
        Node baseNode = loadBasePosition(props);

        System.out.println("board afer " + path + ":");
        Node endNode = addPath(baseNode, path);
        System.out.println(endNode.board);

        KataBrain brain = new KataBrain(props);

        JNodeVal jval = evalNode(props, endNode, brain);
        System.out.println(jval);

        brain.stopKataBrain();

        return baseNode;
    }

    private JNodeVal evalNode(Properties props, Node node, KataBrain brain) throws Exception {

        // step one: calculate the value of this move locally. is it the best local move?
        // evaluate katago for this move in particular
        // eval again for all possible local moves except this one
        // calc the delta
        // we also want to remember the absolute value here. a little tricky because not playing the more empty corner loses points.

        var kresParent = queryNode(brain, node.mom, props);
        System.out.println("parent score: " + df.format(kresParent.blackScore()));
        System.out.println(kresParent.printMoves(1));

        var kres = queryNode(brain, node, props);
        System.out.println("move score: " + df.format(kres.blackScore()));
        System.out.println(kres.printMoves(1));
        var followUps = getGoodFollowups(props, kres);

        // step two: calculate the value of playing here vs a tenuki to a different corner
        // this gives us urgency

        double urgency = calcPassValue(brain, node, kres, props);

        return new JNodeVal(kresParent.blackScore(), kres.blackScore(), urgency);
    }

    private double calcPassValue(KataBrain brain, Node node, KataAnalysisResult kres, Properties props) throws Exception {
        // first, pass
        Node passNode = node.addBasicMove(19, 19);
        KataAnalysisResult karPass = queryNode(brain, passNode, props);

        // clean up
        node.removeChildNode(passNode);

        MoveInfo mi = karPass.moveInfos.get(0);
        System.out.println("pass move: " + mi.move + ", visits: " + mi.visits + ", score: " + df.format(mi.scoreLead) + ", policy: " + df.format(mi.prior * 1000.0));

        return kres.blackScore() - karPass.blackScore();
    }

    private KataAnalysisResult queryNode(KataBrain brain, Node n, Properties props) throws Exception {
        QueryBuilder qb = new QueryBuilder();
        KataQuery query = qb.buildQuery(n);
        query.id = "auto:x";
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        query.maxVisits = Integer.parseInt(props.getProperty("joseki.visits", "1000"));

        restrictToCornerMoves(n, query);

        brain.doQuery(query); // kick off katago
        KataAnalysisResult kres = brain.getResult(query.id, 0);
        return kres;
    }

    // adds to allow moves of query from top right
    private void restrictToCornerMoves(Node n, KataQuery query) {
        var am = new AllowMove();
        am.player = Intersection.color2katagoname(n.getToMove());
        am.untilDepth = 1;
        ArrayList<String> moves = new ArrayList<>();

        int distance = 9;
        for (int x = 18; x > 18 - distance; x--)
            for (int y = 0; y < distance; y++) {
                moves.add(Intersection.toGTPloc(x, y, 19));
            }

        am.moves = moves;
        query.allowMoves = new ArrayList<>();
        query.allowMoves.add(am);
    }

    // adds moves from path to the end of node
    private Node addPath(Node node, String path) throws Exception {
        // path is a comma separated list of moves like "C4,D19,E4"
        String[] moves = path.split(",");
        for (String move : moves) {
            Point p = Intersection.gtp2point(move);
            node = node.addBasicMove(p.x, p.y);
        }
        return node;
    }
}
