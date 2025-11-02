package autoprob.scenario;

import java.util.Properties;

/**
 * Allows GoTool to inject scenario test configuration into JUnit runs.
 */
public final class ScenarioTestContext {
    private static final ThreadLocal<Properties> CONTEXT = new ThreadLocal<>();

    private ScenarioTestContext() {
    }

    public static void set(Properties props) {
        CONTEXT.set(props);
    }

    public static Properties get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
