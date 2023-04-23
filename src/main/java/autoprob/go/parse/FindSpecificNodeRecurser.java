
package autoprob.go.parse;

import autoprob.go.Node;


/**  */
public class FindSpecificNodeRecurser extends NodeRecurser {
    public boolean found = false;
    private Node goal = null;

    /**
     * @param goal what we're looking for
     */
    public FindSpecificNodeRecurser(Node goal) {
        this.goal = goal;
    }

    public void action(Node n) {
        if (n == goal)
            found = true;
    }
}