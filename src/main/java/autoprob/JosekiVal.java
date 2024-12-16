package autoprob;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.CommentAction;
import autoprob.go.action.SizeAction;
import autoprob.go.parse.Parser;
import autoprob.joseki.JMove;
import autoprob.joseki.JNodeVal;
import autoprob.katastruct.AllowMove;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import autoprob.katastruct.MoveInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Point;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;
import java.util.ArrayDeque;

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
        } else if (command.equals("recurse")) {
            runRecurseCommand(props);
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
            moves.add(mi);
        }
        return moves;
    }

    private Node runSinglePosCommand(Properties props) throws Exception {
        return exploreJoseki(props, 1);
    }

    private Node runRecurseCommand(Properties props) throws Exception {
        int nodeLimit = Integer.parseInt(props.getProperty("joseki.explore_limit", "100"));
        return exploreJoseki(props, nodeLimit);
    }

    private Node exploreJoseki(Properties props, int nodeLimit) throws Exception {
        String path = props.getProperty("path");
        if (path == null) {
            throw new RuntimeException("you must pass in a path");
        }
        Node baseNode = loadBasePosition(props);
        baseNode.addAct(new SizeAction(19));

        System.out.println("board afer " + path + ":");
        Node endNode = addPath(baseNode, path);
        System.out.println(endNode.board);

        KataBrain brain = new KataBrain(props);

        evalToLimit(props, endNode, brain, nodeLimit);

        brain.stopKataBrain();

        // output the sgf
        writeSgf(props, baseNode);

        return baseNode;
    }

    private static void writeSgf(Properties props, Node baseNode) throws IOException {
        // set default komi
        baseNode.addXtraTag("KM", "6.5");
        String sgf = "(" + baseNode.outputSGF(true) + ")";
        boolean printSgf = Boolean.parseBoolean(props.getProperty("joseki.print_sgf", "true"));
        if (printSgf) {
            System.out.println(sgf);
        }

        String pathString = props.getProperty("joseki.output_sgf");
        if (pathString != null) {
            Path out_path = Paths.get(pathString);
            byte[] strToBytes = sgf.getBytes();
            Files.write(out_path, strToBytes);
            System.out.println("wrote sgf to " + out_path);
        }
    }

    // recurse to limit, evaluating each node
    private void evalToLimit(Properties props, Node startNode, KataBrain brain, int nodeLimit) throws Exception {
        double minJosekiUrgency = Double.parseDouble(props.getProperty("joseki.min_urgency", "13.0"));
        double maxMistake = Double.parseDouble(props.getProperty("joseki.max_mistake", "0.5"));
        boolean refuteMistakes = Boolean.parseBoolean(props.getProperty("joseki.refute_mistakes", "true"));
        boolean depthFirst = Boolean.parseBoolean(props.getProperty("joseki.depth_first", "false"));
        int writeEvery = Integer.parseInt(props.getProperty("joseki.write_sgf_freq", "100"));
        String urgencyLevels = props.getProperty("joseki.urgency_levels");

        // create stack of moves to consider
        Deque<Node> nodes = new ArrayDeque<>();
        nodes.push(startNode);
        int evalCount = 0;
        while (!nodes.isEmpty()) {
            if (evalCount > 0 && writeEvery > 0 && evalCount % writeEvery == 0) {
                writeSgf(props, startNode.getRoot());
            }

            Node n = depthFirst ? nodes.removeLast() : nodes.pop();
            System.out.println("eval node: " + n.printPath2Here());
            JNodeVal jval = evalNode(props, n, brain);
            System.out.println(jval);
            eval2comment(props, n, jval);

            boolean isMistake = moveScoreDelta(n, jval) * -1 > maxMistake;
            if (!refuteMistakes && isMistake && n != startNode) {
                // remove this
                System.out.println("removing mistake: " + n);
                n.mom.removeChildNode(n);
                evalCount++;
                continue;
            }

            // some characteristics of the result will determine if we should continue

            if (++evalCount >= nodeLimit) {
                break;
            }

            if (notSufficentlyUrgent(jval.urgency(), urgencyLevels, n)) {
                continue;
            }

//            if (jval.urgency() < minJosekiUrgency) {
//                continue;
//            }

            // add moves from jval to stack
            for (int i = jval.moves().size() - 1; i >= 0; i--) {
                Point mv = Intersection.gtp2point(jval.moves().get(i).move());
                Node nextNode = n.addBasicMove(mv.x, mv.y);
                nodes.push(nextNode);
            }
        }

        // any node we didn't get to due to limit, remove
        if (nodes.size() > 0) {
            System.out.println("removing " + nodes.size() + " nodes due to limit");
        } else {
            System.out.println("all nodes evaluated");
        }
        while (!nodes.isEmpty()) {
            Node n = nodes.pop();
            n.mom.removeChildNode(n);
        }
    }

    private boolean notSufficentlyUrgent(double urgency, String urgencyLevels, Node n) {
        if (urgencyLevels == null) {
            return false;
        }

        // looks like: 3=12.0,15=13.2,0=15.0
        String[] levels = urgencyLevels.split(",");
        for (String level: levels) {
            String[] parts = level.split("=");
            int depth = Integer.parseInt(parts[0]);
            double urgencyLevel = Double.parseDouble(parts[1]);
            if (n.depth < depth || depth == 0) {
                if (urgency < urgencyLevel) {
                    System.out.println("urgency too low: " + urgency + " < " + urgencyLevel + " at depth " + n.depth);
                    return true;
                }
                System.out.println("urgency ok: " + urgency + " >= " + urgencyLevel + " at depth " + n.depth);
                return false;
            }
        }

        return false;
    }

    // how good this move was, in perspective of player who played it
    private double moveScoreDelta(Node n, JNodeVal jval) {
        double score = jval.score() - jval.parentScore();
        if (n.getToMove() == Intersection.BLACK) {
            score = -score;
        }
        return score;
    }

    // inserts eval as human readable text on the node
    private void eval2comment(Properties props, Node n, JNodeVal jval) {
        StringBuilder sb = new StringBuilder();
        sb.append(df.format(moveScoreDelta(n, jval)));
        sb.append(" black: ");
        sb.append(df.format(jval.score()));
        sb.append('\n');
        sb.append("parent: " + df.format(jval.parentScore()) + ", urgency: " + df.format(jval.urgency()));
        // add moves
        sb.append('\n');
        sb.append("Followups: ");
        for (JMove move: jval.moves()) {
            sb.append(move.move());
            sb.append(",");
        }

        var kres = jval.kres();
        List<KataAnalysisResult.Policy> top = kres.getTopPolicy(3, kres.humanPolicy);
        sb.append('\n');
        sb.append("human: ");
        for (KataAnalysisResult.Policy pol: top) {
            sb.append(pol.policy);
            sb.append(" at ");
            sb.append(Intersection.toGTPloc(pol.x, pol.y, 19));
            sb.append('\n');
        }

        n.addAct(new CommentAction(sb.toString()));
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

        var moves = new ArrayList<JMove>();
        // create move options from followups
        for (MoveInfo mi: followUps) {
            moves.add(new JMove(mi.move));
        }

        return new JNodeVal(kresParent.blackScore(), kres.blackScore(), urgency, moves, kres);
    }

    private double calcPassValue(KataBrain brain, Node node, KataAnalysisResult kres, Properties props) throws Exception {
        // first, pass
        Node passNode = node.addBasicMove(19, 19);
        KataAnalysisResult karPass = queryNode(brain, passNode, props);

        // clean up
        node.removeChildNode(passNode);

        MoveInfo mi = karPass.moveInfos.get(0);
        System.out.println("pass move: " + mi.move + ", visits: " + mi.visits + ", score: " + df.format(mi.scoreLead) + ", policy: " + df.format(mi.prior * 1000.0));

        return Math.abs(kres.blackScore() - karPass.blackScore());
    }

    private KataAnalysisResult queryNode(KataBrain brain, Node n, Properties props) throws Exception {
        QueryBuilder qb = new QueryBuilder();
        KataQuery query = qb.buildQuery(n);
        query.id = "auto:x";
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        query.maxVisits = Integer.parseInt(props.getProperty("joseki.visits", "1000"));
        query.setHumanSLrank("3d"); // default rank

        int maxMoveDistance = Integer.parseInt(props.getProperty("joseki.max_move_distance", "4"));
        restrictToNearbyMoves(n, query, maxMoveDistance);

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

    private void restrictToNearbyMoves(Node n, KataQuery query, int distance) {
        boolean[][] allowSpots = new boolean[19][19];
        // look for stones on the board and extend from them
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (n.board.board[x][y].stone != Intersection.EMPTY) {
                    for (int dx = -distance; dx <= distance; dx++)
                        for (int dy = -distance; dy <= distance; dy++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx >= 0 && nx < 19 && ny >= 0 && ny < 19) {
                                allowSpots[nx][ny] = true;
                            }
                        }
                }
            }

        var am = new AllowMove();
        am.player = Intersection.color2katagoname(n.getToMove());
        am.untilDepth = 1;
        ArrayList<String> moves = new ArrayList<>();

        // add spots from allowSpots to the list, exclude intersections that are occupied
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (allowSpots[x][y] && n.board.board[x][y].stone == Intersection.EMPTY) {
                    moves.add(Intersection.toGTPloc(x, y, 19));
                }
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
