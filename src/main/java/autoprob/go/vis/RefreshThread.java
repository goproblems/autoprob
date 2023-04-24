package autoprob.go.vis;

import java.awt.Component;

public class RefreshThread extends Thread {
    private Component refreshTarget;
    public boolean    wannadie = false;
    private long      lifeSpan = 2000, born;

    public RefreshThread(Component refreshTarget, int millis) {
        super("Refresher");
        this.refreshTarget = refreshTarget;
        lifeSpan = millis;
        setPriority(Thread.MIN_PRIORITY);
    }

    public void run() {
        born = System.currentTimeMillis();
        while (!wannadie && (System.currentTimeMillis() - born < lifeSpan)) {
            refreshTarget.repaint();
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException e) {
            }
        }
    } // end RUN
}
