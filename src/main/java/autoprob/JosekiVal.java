package autoprob;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Point;
import java.text.DecimalFormat;
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

    private Node runSinglePosCommand(Properties props) throws Exception {
        String path = props.getProperty("path");
        if (path == null) {
            throw new RuntimeException("you must pass in a path");
        }
        String sgf = "(;GM[1]FF[4]CA[UTF-8]SZ[19]RU[Chinese]KM[7.5]SBKV[43.5]OGSC[-0.7]AP[Sabaki:0.52.2];B[dc]SBKV[43.6]OGSC[-0.7];W[cq]SBKV[44.7]OGSC[-0.5];B[qp]SBKV[44.5]OGSC[-0.5];W[ce]SBKV[48.7]OGSC[0.1];B[dp]SBKV[46.1]OGSC[-0.2];W[dq]SBKV[46.0]OGSC[-0.3];B[ep]SBKV[46.0]OGSC[-0.3];W[cp]SBKV[47.0]OGSC[-0.1];B[co]SBKV[40.2]OGSC[-1.2];W[bo]SBKV[40.2]OGSC[-1.2];B[cn]SBKV[40.2]OGSC[-1.2];W[bn]SBKV[40.0]OGSC[-1.2];B[cm]SBKV[40.9]OGSC[-1.1];W[eq]SBKV[47.4]OGSC[-0.1];B[fp]SBKV[40.4]OGSC[-1.1];W[dd]SBKV[50.7]OGSC[0.4];B[cc]SBKV[50.6]OGSC[0.4];W[cg]SBKV[57.9]OGSC[1.6];B[bc]SBKV[25.1]OGSC[-3.9];W[bh]SBKV[46.9]OGSC[-0.2];B[bd]SBKV[9.1]OGSC[-9.1];W[df]SBKV[39.8]OGSC[-1.3])";

        // load SGF
        var parser = new Parser();
        Node node;
        node = parser.parse(sgf);
        node = node.advance2end();

        System.out.println(node.board);

        Node endNode = addPath(node, path);
        System.out.println();
        System.out.println(endNode.board);

        KataBrain brain = new KataBrain(props);

        // step one: calculate the value of this move locally. is it the best local move?
        // evaluate katago for this move in particular
        // eval again for all possible local moves except this one
        // calc the delta
        // we also want to remember the absolute value here. a little tricky because not playing the more empty corner loses points.

        var kres = queryNode(brain, endNode);
        System.out.println("move score: " + df.format(kres.blackScore()));
        System.out.println(kres.printMoves(1));

        var kresParent = queryNode(brain, endNode.mom);
        System.out.println("parent score: " + df.format(kresParent.blackScore()));
        System.out.println(kresParent.printMoves(1));

        // step two: calculate the value of playing here vs a tenuki to a different corner
        // this gives us urgency

        brain.stopKataBrain();

        return node;
    }

    private KataAnalysisResult queryNode(KataBrain brain, Node n) throws Exception {
        QueryBuilder qb = new QueryBuilder();
        KataQuery query = qb.buildQuery(n);
        query.id = "auto:x";
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        brain.doQuery(query); // kick off katago
        KataAnalysisResult kres = brain.getResult(query.id, 0);
        return kres;
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
