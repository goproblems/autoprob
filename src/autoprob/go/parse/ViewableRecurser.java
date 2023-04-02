package autoprob.go.parse;

import autoprob.go.Node;
import autoprob.go.action.MoveAction;

// looks for boundaries from moves

public class ViewableRecurser extends NodeRecurser {
    public int miny, minx, maxx, maxy;

    public ViewableRecurser(int minx, int maxx, int miny, int maxy) {
        this.minx = minx;
        this.maxx = maxx;
        this.miny = miny;
        this.maxy = maxy;
    }

    public void action(Node n) {
        MoveAction ma = n.getMoveAction();
        if (ma != null) {
            int i = ma.loc.x;
            int j = ma.loc.y;
            if (i > maxx)
                maxx = i;
            if (i < minx)
                minx = i;
            if (j > maxy)
                maxy = j;
            if (j < miny)
                miny = j;
        }
    }

}
