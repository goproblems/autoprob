package autoprob.joseki;

import java.util.ArrayList;

public record JNodeVal(double parentScore, double score, double urgency, ArrayList<JMove> moves) {
}
