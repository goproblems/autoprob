package autoprob.collection;

import autoprob.go.Node;

/** Detects whether a problem's starting position belongs to a named collection. */
public interface CollectionDetector {
    String name();

    MatchResult detect(Node startingPosition);

    record MatchResult(boolean matches, String reason) {
        public static MatchResult match(String reason) {
            return new MatchResult(true, reason);
        }

        public static MatchResult noMatch(String reason) {
            return new MatchResult(false, reason);
        }
    }
}
