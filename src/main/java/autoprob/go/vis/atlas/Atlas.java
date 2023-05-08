package autoprob.go.vis.atlas;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Vector;

import javax.swing.JComponent;

import autoprob.go.Node;
import autoprob.go.NodeChangeListener;
import autoprob.go.parse.NodeRecurser;

// the tree of moves
public class Atlas extends JComponent implements MouseListener, NodeChangeListener {
//    protected GoApplet ga;
//    protected Globals  globals;
    /** how far down we've come in each column when plotting positions */
    protected Vector   dropLengths;
    int                sz = 32;
	protected Node rootNode;
    protected Node currentNode;
    private NodeChangeListener sl;

    public Atlas(Node rootNode) {
//        this.ga = ga;
//        this.globals = ga.globals;
        this.rootNode = rootNode;
        this.currentNode = rootNode;
        addMouseListener(this);
//        addKeyListener(ga.gb);
    }
    
    public void setSelectionListener(NodeChangeListener sl) {
    	this.sl = sl;
    }

    public Dimension getPreferredSize() {
//        return new Dimension(1024, 1024);
        // see how big our tree is
        Dimension d = getMinumumSize(); // start here as a min

        // find max width
        MaxDepthRecurser maxDepthRec = new MaxDepthRecurser();
        rootNode.generalPostRecurse(maxDepthRec);

        // max height
        int maxDrop = 0;
        if (dropLengths != null) {
            // calc bottom based on drop -- find max
            for (int i = 0; i < dropLengths.size(); i++) {
                int drop = ((Integer) dropLengths.elementAt(i)).intValue();
                if (drop > maxDrop)
                    maxDrop = drop;
            }
        }

        // now, what if we were to try to draw that position?
        Point p = atlasDrawPos(maxDepthRec.max, maxDrop);
        p.x += sz; // side padding
        p.y += sz; // bottom padding
        if (p.x > d.width)
            d.width = p.x;
        if (p.y > d.height)
            d.height = p.y;

        return d;
    }

    public Dimension getMinumumSize() {
        return new Dimension(512, 128);
    }

    // where would we place a node of given coords?
    public Point atlasDrawPos(int h, int v) {
        int x = (h + 1) * sz;
        int y = (v + 1) * sz;
        return new Point(x, y);
    }

    public Point nodeDrawPos(Node n) {
        return atlasDrawPos(n.depth, n.atlasY);
    }

    // find node closest to where we just clicked
    class LocateRecurser extends NodeRecurser {
        int  closestDist = 30000;
        int  x, y;
        Node closest;

        public LocateRecurser(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public void action(Node n) {
            Point p = nodeDrawPos(n);
            int dist = (int) Math.sqrt((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y));
            if (dist < closestDist) {
                closestDist = dist;
                closest = n;
                // System.out.println("clos: " + Integer.toString(closestDist));
            }
        }
    }

    // from a mouseclick, figure out what node if any they clicked on
    private Node getClickedNode(int x, int y) {
        LocateRecurser locateRec = new LocateRecurser(x, y);
        rootNode.generalPostRecurse(locateRec);
        if (locateRec.closestDist > 30)
            return null; // too durn far
        return locateRec.closest;
    }

    public void mouseReleased(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();

        Node n = getClickedNode(x, y);
        this.currentNode = n;
        repaint();
        if (n == null)
            return; // nada
        if (sl != null)
            sl.newCurrentNode(n);
    }

    public void mousePressed(MouseEvent e) {
    }

    public void mouseClicked(MouseEvent e) {
    	/*
        if (e.isPopupTrigger() || e.isMetaDown()) {
            // only have options for edit mode for now
            if (globals.editMode == false) return;
            
            int x = e.getX();
            int y = e.getY();

            final Node n = getClickedNode(x, y);
            if (n == null)
                return; // nada
            globals.setCurNode(n);
            
            JPopupMenu popup = new JPopupMenu();
            boolean added = false;
            
            // delete
            if (n.mom != null) {
                JMenuItem menuDelete = new JMenuItem("Delete Node");
                popup.add(menuDelete);
                added = true;
                menuDelete.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent arg0) {
                        EditHelper.doCut(globals, ga);
                    }
                });
            }

            // mark correct
            if (n.mom != null) {
                if (n.result != Globals.RIGHT) {
    //            if (n.searchForTheTruth(globals) == false) {
                    JMenuItem menuCorrect = new JMenuItem("Mark Correct");
                    popup.add(menuCorrect);
                    added = true;
                    menuCorrect.addActionListener(new ActionListener() {
                        public void actionPerformed(ActionEvent arg0) {
                            EditHelper.flipRight(globals);
                        }
                    });
                }
                // mark wrong
                else {
                    JMenuItem menuWrong = new JMenuItem("Mark Wrong");
                    popup.add(menuWrong);
                    added = true;
                    menuWrong.addActionListener(new ActionListener() {
                        public void actionPerformed(ActionEvent arg0) {
                            EditHelper.flipRight(globals);
                        }
                    });
                }
            }
            
            if (n.nodeType == Node.TYPE_COMMENT) {
                // convert to normal
                JMenuItem menuVarRight = new JMenuItem("Accept Variation as Right");
                popup.add(menuVarRight);
                added = true;
                menuVarRight.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent arg0) {
                        n.result = Globals.RIGHT;
                        Node xn = n;
                        while (xn != null) {
                            if (xn.nodeType == Node.TYPE_COMMENT)
                                xn.nodeType = Node.TYPE_NORMAL;
                            xn = xn.mom;
                        }
                        globals.updateNode(n);
                    }
                });

                JMenuItem menuVarWrong = new JMenuItem("Accept Variation as Wrong");
                popup.add(menuVarWrong);
                added = true;
                menuVarWrong.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent arg0) {
                        n.result = 0;
                        Node xn = n;
                        while (xn != null) {
                            if (xn.nodeType == Node.TYPE_COMMENT)
                                xn.nodeType = Node.TYPE_NORMAL;
                            xn = xn.mom;
                        }
                        globals.updateNode(n);
                    }
                });
            }
            else {
                if (n.mom != null && n != n.mom.babies.get(0)) {
                    JMenuItem menuUpmost = new JMenuItem("Move Upmost");
                    popup.add(menuUpmost);
                    added = true;
                    menuUpmost.addActionListener(new ActionListener() {
                        public void actionPerformed(ActionEvent arg0) {
                            EditHelper.moveUpmost(globals, ga);
                        }
                    });
                }
            }

            if (added) {
                popup.show(e.getComponent(), e.getX(), e.getY());
            }
            return;
        }
        */
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
    }

    class MaxDepthRecurser extends NodeRecurser {
        int max = 0;

        public MaxDepthRecurser() {
        }

        public void action(Node n) {
            if (n.depth > max)
                max = n.depth;
        }
    }

    class ResetAtlasRecurser extends NodeRecurser {
        public ResetAtlasRecurser() {
        }

        public void action(Node n) {
            n.atlasY = -1;
        }
    }

    // recurse through this node and see where to place each subnode
    public void calculatePositions(Node n) {
        // reset all positions when we calc new
        n.generalPostRecurse(new ResetAtlasRecurser());

        MaxDepthRecurser maxDepthRec = new MaxDepthRecurser();
        n.generalPostRecurse(maxDepthRec);
        // System.out.println("max depth: " +
        // Integer.toString(maxDepthRec.max));

        dropLengths = new Vector(); // allocate space for it
        // fill with -1's
        for (int i = 0; i < maxDepthRec.max + 1; i++)
            dropLengths.addElement(-1);

        n.generalPostRecurse(new AtlasPosRecurser(dropLengths));

        // we might have changed size, so let's recalc container pane
//        if (ga.atlasPane != null)
//            ga.atlasPane.doLayout();
        ensureVisible();
    }

    // can we see our current node?
    public boolean ensureVisible() {
    	return true;
//
//    	if (ga.atlasPane == null)
//            return true;
//
//        // are we off the map? if so, let's move
//        Node cur = globals.getCurNode();
//        if (cur == null)
//            return true;
//
//        Point p = nodeDrawPos(cur);
//        //Rectangle r = new Rectangle(p.x, p.y, p.x, p.y);
//        Rectangle r = new Rectangle(p.x, p.y, 0, 0);
//        r.grow(64, 64);
//        scrollRectToVisible(r);

//        Point scroll = ga.atlasPane.getScrollPosition();
//        Dimension vsize = ga.atlasPane.getViewportSize();
//        Rectangle r = new Rectangle(scroll.x, scroll.y, vsize.width, vsize.height);
//        r.grow(-2, -2);
//        if (!r.contains(p)) {
//            // i like to move it move it
//            // try to center
//            ga.atlasPane.setScrollPosition(p.x - vsize.width / 2, p.y - vsize.height / 2);
//            return false;
//        }
    }

    public void newCurrentNode(Node node) {
        ensureVisible();
        repaint();
    }

    public void nodeChanged(Node node) {
        repaint();
    }
}
