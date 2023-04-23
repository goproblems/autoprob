package autoprob.go;

/**
 * node changed or we changed which is current
 */
public interface NodeChangeListener {
    public void newCurrentNode(Node node);
    public void nodeChanged(Node node);
}
