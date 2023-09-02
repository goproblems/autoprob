package autoprob;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.NodeChangeListener;
import autoprob.go.vis.BasicGoban;

import java.awt.*;
import java.util.Properties;

public class ShapePathCreator extends PathCreator {

    public ShapePathCreator(ProblemDetector det, Properties props, KataBrain brain) {
        super(det, props, brain);
    }

    // create problem branches
    public void makePaths(Node problem, BasicGoban probGoban, GenOptions gopts, NodeChangeListener ncl) throws Exception {
        this.probGoban = probGoban;
        this.gopts = gopts;
        this.ncl = ncl;

        System.out.println("==> starting shape path creation");

        problem.markCrayons();
    }
}
