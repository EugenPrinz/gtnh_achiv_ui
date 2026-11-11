package achievementsnh;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class Config {

    public static final String CAT = Configuration.CATEGORY_GENERAL;

    /** Kept for the in-game config screen (mod list → Config). */
    public static Configuration config;

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
    public static boolean showFps = true;
    /** Opacity of the dark backdrop behind the window, 0..100. */
    public static int backgroundOpacity = 85;
    public static boolean showMinimap = true;
    /** Page names (as shown) with automatic tree layout from parent links. */
    public static final java.util.Set<String> autoLayoutPages = new java.util.LinkedHashSet<>();

    private Config() {}

    public static void load(File file) {
        config = new Configuration(file);
        sync();
    }

    /** Reads all values; called on load and after the config screen saves. */
    public static void sync() {
        Configuration c = config;
        enabled = c
            .getBoolean("enabled", CAT, enabled, "Replace the achievements screen with the Achievements NH one.");
        hideSpoilers = c.getBoolean(
            "hideSpoilers",
            CAT,
            hideSpoilers,
            "Vanilla spoiler rules: hide locked achievements far from anything unlocked.");
        panelWidthPercent = c
            .getInt("panelWidthPercent", CAT, panelWidthPercent, 50, 100, "Window width, % of screen.");
        panelHeightPercent = c
            .getInt("panelHeightPercent", CAT, panelHeightPercent, 50, 100, "Window height, % of screen.");
        sidebarWidth = c.getInt("sidebarWidth", CAT, sidebarWidth, 60, 300, "Page list width, GUI pixels.");
        detailsWidth = c.getInt("detailsWidth", CAT, detailsWidth, 80, 400, "Details panel width, GUI pixels.");
        maxRows = c.getInt("maxRows", CAT, maxRows, 50, 5000, "Maximum rows shown in the achievement list.");
        showFps = c.getBoolean("showFps", CAT, showFps, "Show the screen's frame rate in the footer.");
        backgroundOpacity = c.getInt(
            "backgroundOpacity",
            CAT,
            backgroundOpacity,
            0,
            100,
            "Opacity of the dark backdrop behind the window, %. 0 = only the usual vanilla dimming.");
        showMinimap = c.getBoolean("showMinimap", CAT, showMinimap, "Show the tree overview when a page does not fit.");
        autoLayoutPages.clear();
        java.util.Collections.addAll(
            autoLayoutPages,
            c.getStringList(
                "autoLayoutPages",
                CAT,
                new String[0],
                "Pages (names as shown) where the tree is laid out from parent links. Toggle with the Auto button."));
        if (c.hasChanged()) c.save();
    }

    /** Persists the auto-layout toggle of a page. */
    public static void setAutoLayout(String page, boolean on) {
        if (on) autoLayoutPages.add(page);
        else autoLayoutPages.remove(page);
        config.get(CAT, "autoLayoutPages", new String[0])
            .set(autoLayoutPages.toArray(new String[0]));
        config.save();
    }
}
