package achievementsnh.data;

public enum Filter {

    ALL("achievementsnh.filter.all"),
    AVAILABLE("achievementsnh.filter.available"),
    UNLOCKED("achievementsnh.filter.unlocked"),
    LOCKED("achievementsnh.filter.locked");

    public final String langKey;

    Filter(String langKey) {
        this.langKey = langKey;
    }

    public boolean accepts(Status status) {
        return switch (this) {
            case ALL -> true;
            case AVAILABLE -> status == Status.AVAILABLE;
            case UNLOCKED -> status == Status.UNLOCKED;
            case LOCKED -> status == Status.LOCKED;
        };
    }
}
