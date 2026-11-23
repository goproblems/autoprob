package autoprob.validate.cases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import autoprob.validate.DecisionRecord;

class DecisionCaseTest {
    private static DecisionCase kase(String role, List<String> choose, List<String> avoid) {
        DecisionCase c = new DecisionCase();
        c.role = role;
        c.expect.choose.addAll(choose);
        c.expect.avoid.addAll(avoid);
        return c;
    }

    private static DecisionRecord decision(String role, String chose, String... alternatives) {
        DecisionRecord d = new DecisionRecord();
        d.role = role;
        d.chose = chose;
        d.alternatives = new ArrayList<>(List.of(alternatives));
        return d;
    }

    @Test
    void casesBelowALineThatMustEndAreMoot() {
        CaseFile cf = new CaseFile();
        DecisionCase end = kase(DecisionRecord.OPPONENT, List.of(DecisionRecord.END), List.of("W M1"));
        end.at.addAll(List.of("B L1", "W N1", "B J1"));
        DecisionCase below = kase(DecisionRecord.OPPONENT, List.of("W L2"), List.of());
        below.at.addAll(List.of("B L1", "W N1", "B J1", "W M1", "B K3"));
        DecisionCase beside = kase(DecisionRecord.OPPONENT, List.of("W M1"), List.of());
        beside.at.addAll(List.of("B L1", "W N1", "B K3"));
        cf.decisions.addAll(List.of(end, below, beside));
        assertTrue(cf.moot(below));
        assertFalse(cf.moot(beside));
        assertFalse(cf.moot(end));
        // either way above leaves the line free to go on
        end.expect.choose.add("W M1");
        assertFalse(cf.moot(below));
    }

    @Test
    void eitherWayAcceptsEndingOrGoingOn() {
        // after a solver move: the line may end, or go on with the accepted resistance
        DecisionCase opponent = kase(DecisionRecord.OPPONENT, List.of("W G2", DecisionRecord.END), List.of());
        assertTrue(opponent.passes(decision(DecisionRecord.OPPONENT, DecisionRecord.END)));
        assertTrue(opponent.passes(decision(DecisionRecord.OPPONENT, "W G2")));
        assertFalse(opponent.passes(decision(DecisionRecord.OPPONENT, "W H2")));
        // after an opponent move: the line may end, or go on with every move that works
        DecisionCase solver = kase(DecisionRecord.SOLVER, List.of("B C1", "B D1", DecisionRecord.END), List.of());
        assertTrue(solver.passes(decision(DecisionRecord.SOLVER, DecisionRecord.END)));
        assertTrue(solver.passes(decision(DecisionRecord.SOLVER, "B C1", "B D1")));
        assertFalse(solver.passes(decision(DecisionRecord.SOLVER, "B C1")));
        // END alone still means the line must end
        DecisionCase mustEnd = kase(DecisionRecord.SOLVER, List.of(DecisionRecord.END), List.of());
        assertFalse(mustEnd.passes(decision(DecisionRecord.SOLVER, "B C1")));
        assertTrue(mustEnd.passes(decision(DecisionRecord.SOLVER, DecisionRecord.END)));
    }

    @Test
    void solverCasesNeedEveryWorkingMoveInTheTree() {
        DecisionCase c = kase(DecisionRecord.SOLVER, List.of("B D1", "B E1"), List.of());
        assertFalse(c.passes(decision(DecisionRecord.SOLVER, "B D1")));
        assertTrue(c.passes(decision(DecisionRecord.SOLVER, "B D1", "B E1")));
        // an extra move nobody judged is fine
        assertTrue(c.passes(decision(DecisionRecord.SOLVER, "B E1", "B D1", "B K1")));
    }

    @Test
    void noChosenMoveMayBeAvoided() {
        DecisionCase c = kase(DecisionRecord.SOLVER, List.of("B D1"), List.of("B K1"));
        assertFalse(c.passes(decision(DecisionRecord.SOLVER, "B D1", "B K1")));
        DecisionCase o = kase(DecisionRecord.OPPONENT, List.of(), List.of("W N1"));
        assertFalse(o.passes(decision(DecisionRecord.OPPONENT, "W N1")));
        assertTrue(o.passes(decision(DecisionRecord.OPPONENT, "W F1")));
    }

    @Test
    void opponentCasesAcceptAnyListedResistance() {
        DecisionCase c = kase(DecisionRecord.OPPONENT, List.of("W F1", "W H1"), List.of());
        assertTrue(c.passes(decision(DecisionRecord.OPPONENT, "W H1")));
        assertFalse(c.passes(decision(DecisionRecord.OPPONENT, "W N1")));
        assertTrue(c.passes(decision(DecisionRecord.OPPONENT, "W N1", "W F1")));
        DecisionCase end = kase(DecisionRecord.OPPONENT, List.of(DecisionRecord.END), List.of());
        assertTrue(end.passes(decision(DecisionRecord.OPPONENT, DecisionRecord.END)));
    }
}
