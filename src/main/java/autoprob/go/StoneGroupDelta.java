package autoprob.go;

public class StoneGroupDelta {
    public final StoneGroup groupA, groupB;
    public final double ownershipDelta;

    public StoneGroupDelta(StoneGroup groupA, StoneGroup groupB) {
        this.groupA = groupA;
        this.groupB = groupB;
        ownershipDelta = groupB.ownership - groupA.ownership;
    }
}
