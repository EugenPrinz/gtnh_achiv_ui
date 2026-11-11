package achievementsnh;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class Config {

    private static final String CAT = Configuration.CATEGORY_GENERAL;

    /** Replace the vanilla / Better Achievements screen. */
    public static boolean enabled = true;
    /** Vanilla behaviour: hide locked achievements more than 3 steps away and mask the name at 3 steps. */
    public static boolean hideSpoilers = false;
    public static int panelWidthPercent = 94;
    public static int panelHeightPercent = 92;
    public static int sidebarWidth = 112;
    public static int detailsWidth = 140;
    /** Upper bound of rows rendered in the list (search across all pages can match thousands). */
    public static int maxRows = 600;

    private Config() {}

    public static void load(File file) {
        Configuration c = new Configuration(file);
        enabled = c.getBoolean("enabled", CAT, enabled, "Replace the achievements screen with the Achievements NH one.");
        hideSpoilers = c.getBoolean(
            "hideSpoilers",
            CAT,
            hideSpoilers,
            "Vanilla spoiler rules: hide locked achievements far from anything unlocked.");
        panelWidthPercent = c.getInt("panelWidthPercent", CAT, panelWidthPercent, 50, 100, "Window width, % of screen.");
        panelHeightPercent = c
            .getInt("panelHeightPercent", CAT, panelHeightPercent, 50, 100, "Window height, % of screen.");
        sidebarWidth = c.getInt("sidebarWidth", CAT, sidebarWidth, 60, 300, "Page list width, GUI pixels.");
        detailsWidth = c.getInt("detailsWidth", CAT, detailsWidth, 80, 400, "Details panel width, GUI pixels.");
        maxRows = c.getInt("maxRows", CAT, maxRows, 50, 5000, "Maximum rows shown in the achievement list.");
        if (c.hasChanged()) c.save();
    }
}
