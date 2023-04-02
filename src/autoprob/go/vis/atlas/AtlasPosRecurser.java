package autoprob.go.vis.atlas;

import java.util.Vector;

import autoprob.go.Node;
import autoprob.go.parse.NodeRecurser;


class AtlasPosRecurser extends NodeRecurser {
    private Vector dropLengths;
    
    public AtlasPosRecurser(Vector dropLengths) {
        this.dropLengths = dropLengths;
    }

    public void action(Node n) {
        if (n.atlasY >= 0)
            return; // already been set by one of our caring children

        // let's find out how deep we need to be
        // find the drop length of all parents that don't have an atlasY
        // set, and take the max + 1 of this

        // search for node "ret" -- the first node up the food chain that
        // has a mom who has a position already set
        Node ret = n;
        while (ret.mom != null) {
            if (ret.mom.atlasY >= 0)
                break; // already been set
            ret = ret.mom;
        }
        // System.out.println("run length: " + (n.depth - ret.depth));

        // this then is our run-length: from Node n to Node ret

        // now we know our run-length
        // let's find the max drop in this length
        int maxDrop = -1;
        for (int i = ret.depth; i <= n.depth; i++) {
            int d = ((Integer) dropLengths.elementAt(i)).intValue();
            // System.out.println("ad: " + Integer.toString(d));
            if (d > maxDrop)
                maxDrop = d;
        }

        int drop = maxDrop + 1; // our drop
        if ((n.mom != null) && (n.mom.atlasY > drop))
            drop = n.mom.atlasY; // can't be higher up than mommy -- that
                                    // wouldn't be very filial

        // now update drops and positions
        Node nd = n;
        do {
            dropLengths.setElementAt(new Integer(drop), nd.depth);
            nd.atlasY = drop;
            // System.out.println("atlas: " + Integer.toString(nd.depth) +
            // ", " + Integer.toString(drop));
            if (nd.mom == null || nd == ret)
                break;
            nd = nd.mom;
        } while (true);

        // now, let's account for how we draw the connection between us and
        // mommy -- we might need to reserve some vertical space for that --
        // and not only that, but her parents too
        // so..... draw a diagonal line back upwards
        Node diag = ret.mom;
        while (diag != null) {
            int odrop = ((Integer) dropLengths.elementAt(diag.depth)).intValue();
            int min = drop - (ret.depth - diag.depth); // minimum drop here
            if (odrop < min)
                dropLengths.setElementAt(new Integer(min), diag.depth);
            // if (diag != null && diag.atlasY >= 0)
            // break; // no need to look further
            // if (diag.babies.size() > 0)
            // break;
            diag = diag.mom; // move on down
        }
        /*
         * if (ret.mom != null && ret.atlasY >= ret.mom.atlasY + 2) {
         * dropLengths.setElementAt(new Integer(ret.atlasY - 1),
         * ret.mom.depth); }
         */
    }
}
