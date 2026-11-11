package achievementsnh.data;

public enum Sort {

    /** Registration order, as the mod added them. */
    DEFAULT("achievementsnh.sort.default"),
    /** Available first, then locked, then unlocked; registration order inside each group. */
    STATUS("achievementsnh.sort.status"),
    NAME("achievementsnh.sort.name");

    public final String langKey;

    Sort(String langKey) {
        this.langKey = langKey;
    }

    public Sort next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static int statusRank(Status s) {
        return switch (s) {
            case AVAILABLE -> 0;
            case LOCKED -> 1;
            case UNLOCKED -> 2;
        };
    }
}
