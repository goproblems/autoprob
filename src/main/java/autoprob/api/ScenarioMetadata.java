package autoprob.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata settings for a Scenario, set by the problem creator.
 * Includes config overrides and board area limits.
 */
public class ScenarioMetadata {
    /** Version number */
    public int version = 1;

    /** Key positions to watch, in A1 format (e.g. ["Q16", "R16"]) */
    public List<String> targetPositions = new ArrayList<>();

    /** Where the player CAN move. Empty list = anywhere on the board. */
    public List<RectangleArea> playerAllowedAreas = new ArrayList<>();

    /** Where the player CANNOT move, even inside allowed areas. */
    public List<RectangleArea> playerDisallowedAreas = new ArrayList<>();

    /** Where the computer CAN respond. Empty list = anywhere on the board. */
    public List<RectangleArea> computerAllowedAreas = new ArrayList<>();

    /** Where the computer CANNOT respond. */
    public List<RectangleArea> computerDisallowedAreas = new ArrayList<>();

    /** Config values that override defaults for this scenario (e.g. "scenario.analysis.visits" -> 2000). */
    public Map<String, Object> configOverrides = new HashMap<>();

    public ScenarioMetadata() {}

    /** True if the player can move at this point. */
    public boolean isPlayerMoveAllowed(java.awt.Point p) {
        return isMoveAllowed(p, playerAllowedAreas, playerDisallowedAreas);
    }

    /** True if the computer can respond at this point. */
    public boolean isComputerMoveAllowed(java.awt.Point p) {
        return isMoveAllowed(p, computerAllowedAreas, computerDisallowedAreas);
    }

    /** True if any area limits are set for the player. */
    public boolean hasPlayerAreaConstraints() {
        return !playerAllowedAreas.isEmpty() || !playerDisallowedAreas.isEmpty();
    }

    /** True if any area limits are set for the computer. */
    public boolean hasComputerAreaConstraints() {
        return !computerAllowedAreas.isEmpty() || !computerDisallowedAreas.isEmpty();
    }

    private static boolean isMoveAllowed(java.awt.Point p, List<RectangleArea> allowed, List<RectangleArea> disallowed) {
        if (!allowed.isEmpty()) {
            boolean inAllowed = false;
            for (RectangleArea area : allowed) {
                if (area.contains(p)) { inAllowed = true; break; }
            }
            if (!inAllowed) return false;
        }
        for (RectangleArea area : disallowed) {
            if (area.contains(p)) return false;
        }
        return true;
    }
}
