package achievementsnh.data;

public enum Status {

    UNLOCKED(0xFF2E7D32, "achievementsnh.status.unlocked"),
    AVAILABLE(0xFF1565C0, "achievementsnh.status.available"),
    LOCKED(0xFF6E6E6E, "achievementsnh.status.locked");

    /** ARGB text colour, tuned for the light-grey vanilla panel. */
    public final int color;
    public final String langKey;

    Status(int color, String langKey) {
        this.color = color;
        this.langKey = langKey;
    }
}
