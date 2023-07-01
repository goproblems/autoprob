package autoprob.util;

import autoprob.go.Node;

public interface KataObserver {
    /**
     * for the left board, look in function testAnalyze. right after the line KataAnalysisResult kres = brain.getResult(query.id, targetTurn);
     * draw the board according to the variable n
     * <p>
     * in the same function, after foundCount++;, draw the right board according to n at this point
     * <p>
     * problems found in the UI is equal to foundCount
     * <p>
     * stone delta is ownDeltaB plus ownDeltaW from detector
     * <p>
     * solutions is numSols
     */


    void updateLeftBoard(Node node);

    void updateRightBoard(Node node);

    void updateFoundCount(int foundCount);

    void updateStoneDelta(double stoneDelta);

    void updateSolutions(double solutions);

    void updateGamesSearched(int gamesSearched);

}
