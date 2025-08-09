package autoprob.problem;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculates Go ranks from Elo ratings
 * Based on the TypeScript implementation in elo.ts
 */
public class EloRankCalculator {
    // Constants from EloLevelInterface
    private static final int FIRST_DAN_LEVEL = 30;
    private static final int LAST_KNOWN_KYU_LEVEL = 29;
    private static final int ELO_LEVEL_BASE = 700;
    private static final int ELO_LEVEL_GAP = 28;
    private static final int ELO_LOW_LEVEL_COUNT = 3;
    private static final int ELO_LEVEL_GAP_LOW = 70;
    private static final int ELO_LEVEL_6_DAN = 1730;
    private static final int ELO_LEVEL_7_DAN = 1800;
    private static final int ELO_LEVEL_8_DAN = 1930;
    private static final int ELO_LEVEL_9_DAN = 2007;
    
    // Constants from EloCalculatorInterface
    private static final double POST_9_DAN_MIDPOINT_ELO = 2340;
    private static final double POST_9_DAN_STEEPNESS_FACTOR = 0.01;
    
    /**
     * Calculate the rank name from an Elo rating
     */
    public static String calculateEloLevelName(double elo, boolean precision) {
        double level;
        if (precision) {
            level = convertEloToLevelWithPrecision(elo);
        } else {
            level = convertEloToLevel(elo);
        }
        return getLevelName(level, precision);
    }
    
    /**
     * Calculate the rank name from an Elo rating (no precision)
     */
    public static String calculateEloLevelName(double elo) {
        return calculateEloLevelName(elo, false);
    }
    
    /**
     * Calculate the short rank name from an Elo rating (e.g., "5k", "2d")
     */
    public static String calculateEloShortLevelName(double elo) {
        return getShortLevelName(convertEloToLevel(elo));
    }
    
    /**
     * Get the level name from a level number
     */
    public static String getLevelName(double level, boolean precision) {
        int flooredLevel = (int) Math.floor(level);
        double precisionValue = level - flooredLevel;
        
        double value;
        if (flooredLevel < 30) {
            value = 30 - flooredLevel;
        } else {
            value = flooredLevel - 29;
        }
        
        String valueStr;
        if (precision) {
            value = value + precisionValue;
            valueStr = String.format("%.2f", value);
        } else {
            valueStr = String.valueOf((int) Math.floor(value));
        }
        
        return valueStr + " " + (level < 30 ? "kyu" : "dan");
    }
    
    /**
     * Get the short level name from a level number
     */
    public static String getShortLevelName(int level) {
        int value;
        if (level < FIRST_DAN_LEVEL) {
            value = FIRST_DAN_LEVEL - level;
        } else {
            value = level - LAST_KNOWN_KYU_LEVEL;
        }
        
        return value + (level < FIRST_DAN_LEVEL ? "k" : "d");
    }
    
    /**
     * Get the Elo cutoffs for each level
     */
    public static List<Integer> getEloLevelCutoffs() {
        List<Integer> cutoffs = new ArrayList<>();
        cutoffs.add(0); // 30k
        
        for (int level = 1; level < 39; level++) {
            // Basic calculation is a linear function on level
            int elo = ELO_LEVEL_BASE + level * ELO_LEVEL_GAP;
            
            // Handle low levels specially, to spread out a bit
            if (level < ELO_LOW_LEVEL_COUNT) {
                int lowBase = ELO_LEVEL_BASE - ELO_LOW_LEVEL_COUNT * ELO_LEVEL_GAP_LOW;
                lowBase += ELO_LEVEL_GAP * ELO_LOW_LEVEL_COUNT;
                elo = lowBase + level * ELO_LEVEL_GAP_LOW;
            }
            
            cutoffs.add(elo);
        }
        
        // Override known values for high dan levels
        cutoffs.set(30 - 1 + 6, ELO_LEVEL_6_DAN);
        cutoffs.set(30 - 1 + 7, ELO_LEVEL_7_DAN);
        cutoffs.set(30 - 1 + 8, ELO_LEVEL_8_DAN);
        cutoffs.set(30 - 1 + 9, ELO_LEVEL_9_DAN);
        
        return cutoffs;
    }
    
    /**
     * Convert a level to an Elo rating
     */
    public static int convertLevelToElo(int level) {
        List<Integer> cutoffs = getEloLevelCutoffs();
        return cutoffs.get(level);
    }
    
    /**
     * Convert an Elo rating to a level (integer)
     */
    public static int convertEloToLevel(double elo) {
        List<Integer> cutoffs = getEloLevelCutoffs();
        
        for (int level = 38; level >= 1; level--) {
            if (elo >= cutoffs.get(level)) {
                return level;
            }
        }
        
        return 0; // 30k catches all the low end
    }
    
    /**
     * Convert an Elo rating to a level with precision (decimal)
     */
    public static double convertEloToLevelWithPrecision(double elo) {
        List<Integer> cutoffs = getEloLevelCutoffs();
        
        for (int level = 38; level >= 1; level--) {
            if (elo == cutoffs.get(level)) {
                return level;
            }
            if (elo > cutoffs.get(level)) {
                double precision = 0;
                
                if (level < 38) {
                    precision = ((elo - cutoffs.get(level)) / 
                                (cutoffs.get(level + 1) - cutoffs.get(level))) * 100;
                } else {
                    // Maximum rating can be 9.99 dan
                    precision = Math.min(99.0,
                        100.0 / (1.0 + Math.exp(
                            -POST_9_DAN_STEEPNESS_FACTOR * 
                            (elo - POST_9_DAN_MIDPOINT_ELO)
                        ))
                    );
                }
                
                if (level < FIRST_DAN_LEVEL) {
                    precision = 100 - precision;
                }
                
                return level + precision / 100;
            }
        }
        
        return 0; // 30k catches all the low end
    }
}