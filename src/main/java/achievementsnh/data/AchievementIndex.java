package achievementsnh.data;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.Achievement;
import net.minecraft.stats.AchievementList;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.AchievementPage;

import achievementsnh.AchievementsNH;
import achievementsnh.Config;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Snapshot of all achievement pages, built when the screen opens. Static data (names, icons, tree links) is cached;
 * status is always read live from the client {@link StatFileWriter}.
 */
@SideOnly(Side.CLIENT)
public final class AchievementIndex {

    public static final class PageInfo {

        public final int index;
        public final String name;
        public final ItemStack icon;
        public final List<Achievement> achievements;
        /** Not a real AchievementPage (the pinned list): contents come from other pages. */
        public final boolean virtual;

        PageInfo(int index, String name, ItemStack icon, List<Achievement> achievements, boolean virtual) {
            this.index = index;
            this.name = name;
            this.icon = icon;
            this.achievements = achievements;
            this.virtual = virtual;
        }
    }

    private final List<PageInfo> pages = new ArrayList<>();
    private final List<Achievement> all = new ArrayList<>();
    private final List<Achievement> pinned = new ArrayList<>();
    private PageInfo pinnedPage;
    private final Map<Achievement, PageInfo> pageOf = new IdentityHashMap<>();
    private final Map<Achievement, List<Achievement>> children = new IdentityHashMap<>();
    private final Map<Achievement, String> names = new IdentityHashMap<>();
    private final Map<Achievement, String> descriptions = new IdentityHashMap<>();
    private final Map<Achievement, String> searchText = new IdentityHashMap<>();

    public AchievementIndex() {
        build();
    }

    // ---- building -------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void build() {
        List<Achievement> vanilla = new ArrayList<>();
        for (Object o : AchievementList.achievementList) {
            Achievement a = (Achievement) o;
            if (!AchievementPage.isAchievementInPages(a)) vanilla.add(a);
        }
        addPage("Minecraft", new ItemStack(Blocks.grass), vanilla);

        int count = AchievementPage.getAchievementPages()
            .size();
        for (int i = 0; i < count; i++) {
            AchievementPage page = AchievementPage.getAchievementPage(i);
            if (page == null) continue;
            List<Achievement> list = new ArrayList<>(page.getAchievements());
            addPage(StatCollector.translateToLocal(page.getName()), pageIcon(page, list), list);
        }

        for (Achievement a : all) {
            if (a.parentAchievement != null) {
                children.computeIfAbsent(a.parentAchievement, k -> new ArrayList<>())
                    .add(a);
            }
        }

        // pinned: virtual page, always last in `pages` so real page indices stay stable
        Map<String, Achievement> byId = new java.util.HashMap<>();
        for (Achievement a : all) byId.put(a.statId, a);
        for (String id : Pins.load()) {
            Achievement a = byId.get(id);
            if (a != null && !pinned.contains(a)) pinned.add(a);
        }
        pinnedPage = new PageInfo(
            pages.size(),
            "\u2605 " + I18n.format("achievementsnh.pages.pinned"),
            new ItemStack(Items.nether_star),
            Collections.unmodifiableList(pinned),
            true);
        pages.add(pinnedPage);
    }

    // ---- pins -----------------------------------------------------------------------------------------------------

    public PageInfo pinnedPage() {
        return pinnedPage;
    }

    public boolean isPinned(Achievement a) {
        return pinned.contains(a);
    }

    /** Toggles and saves; returns the new state. */
    public boolean togglePin(Achievement a) {
        boolean now;
        if (pinned.remove(a)) {
            now = false;
        } else {
            pinned.add(a);
            now = true;
        }
        List<String> ids = new ArrayList<>();
        for (Achievement p : pinned) ids.add(p.statId);
        Pins.save(ids);
        return now;
    }

    private void addPage(String name, ItemStack icon, List<Achievement> list) {
        PageInfo info = new PageInfo(pages.size(), name, icon, Collections.unmodifiableList(list), false);
        pages.add(info);
        for (Achievement a : list) {
            if (pageOf.containsKey(a)) continue; // achievement registered on several pages: first one wins
            pageOf.put(a, info);
            all.add(a);
            String n = safe(
                () -> a.func_150951_e()
                    .getUnformattedText(),
                a.statId);
            String d = safe(a::getDescription, "");
            names.put(a, n);
            descriptions.put(a, d);
            searchText.put(a, (strip(n) + "\n" + strip(d)).toLowerCase(Locale.ROOT));
        }
    }

    private static ItemStack pageIcon(AchievementPage page, List<Achievement> list) {
        ItemStack fromBA = betterAchievementsIcon(page);
        if (fromBA != null) return fromBA;
        for (Achievement a : list) {
            if (a.parentAchievement == null && a.theItemStack != null) return a.theItemStack;
        }
        for (Achievement a : list) {
            if (a.theItemStack != null) return a.theItemStack;
        }
        return new ItemStack(Items.book);
    }

    private static Method baInstance, baGetItemStack;
    private static boolean baChecked;

    /** Reuses page icons chosen in Better Achievements (config / IMC / middle-click), if it is installed. */
    private static ItemStack betterAchievementsIcon(AchievementPage page) {
        if (!baChecked) {
            baChecked = true;
            if (Loader.isModLoaded("BetterAchievements")) {
                try {
                    Class<?> c = Class.forName("betterachievements.registry.AchievementRegistry");
                    baInstance = c.getMethod("instance");
                    baGetItemStack = c.getMethod("getItemStack", AchievementPage.class);
                } catch (Throwable t) {
                    AchievementsNH.LOG.warn("Better Achievements found but its icon registry is not accessible", t);
                }
            }
        }
        if (baGetItemStack == null) return null;
        try {
            return (ItemStack) baGetItemStack.invoke(baInstance.invoke(null), page);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- queries --------------------------------------------------------------------------------------------------

    public List<PageInfo> pages() {
        return pages;
    }

    public PageInfo pageOf(Achievement a) {
        return pageOf.get(a);
    }

    public List<Achievement> childrenOf(Achievement a) {
        List<Achievement> list = children.get(a);
        return list == null ? Collections.emptyList() : list;
    }

    public static StatFileWriter stats() {
        return Minecraft.getMinecraft().thePlayer.getStatFileWriter();
    }

    public static Status status(Achievement a) {
        StatFileWriter s = stats();
        if (s.hasAchievementUnlocked(a)) return Status.UNLOCKED;
        if (s.canUnlockAchievement(a)) return Status.AVAILABLE;
        return Status.LOCKED;
    }

    /** Steps to the nearest unlocked ancestor (vanilla spoiler distance). */
    private static int distance(Achievement a) {
        return stats().func_150874_c(a);
    }

    public boolean isHidden(Achievement a) {
        return Config.hideSpoilers && status(a) == Status.LOCKED && distance(a) > 3;
    }

    /** Sorts in place according to {@code sort}; stable, so registration order breaks ties. */
    public void sort(List<Achievement> list, Sort sort) {
        switch (sort) {
            case STATUS -> {
                Map<Achievement, Integer> rank = new IdentityHashMap<>();
                for (Achievement a : list) rank.put(a, Sort.statusRank(status(a)));
                list.sort((x, y) -> Integer.compare(rank.get(x), rank.get(y)));
            }
            case NAME -> list.sort((x, y) -> sortName(x).compareTo(sortName(y)));
            default -> {}
        }
    }

    private String sortName(Achievement a) {
        String text = searchText.get(a);
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    public static String normalizeQuery(String query) {
        return query == null ? ""
            : query.trim()
                .toLowerCase(Locale.ROOT);
    }

    /** Text query match (name + description, formatting stripped). Masked achievements never match. */
    public boolean matchesQuery(Achievement a, String query) {
        return matchesNormalized(a, normalizeQuery(query));
    }

    /** Same as {@link #matchesQuery} for a query already passed through {@link #normalizeQuery}. */
    public boolean matchesNormalized(Achievement a, String q) {
        if (q.isEmpty()) return true;
        String text = searchText.get(a);
        return text != null && !isMasked(a) && text.contains(q);
    }

    public boolean isMasked(Achievement a) {
        return Config.hideSpoilers && status(a) == Status.LOCKED && distance(a) == 3;
    }

    public String name(Achievement a) {
        return isMasked(a) ? I18n.format("achievement.unknown") : names.get(a);
    }

    public String description(Achievement a) {
        if (isMasked(a)) return "";
        if (Config.hideSpoilers && status(a) == Status.LOCKED && a.parentAchievement != null) {
            return I18n.format("achievement.requires", names.get(a.parentAchievement));
        }
        return descriptions.get(a);
    }

    /**
     * Achievements to list.
     *
     * @param page   page index; ignored when {@code query} is not blank (search runs over all pages)
     * @param query  free text matched against name and description
     * @param filter status filter
     */
    public List<Achievement> query(int page, String query, Filter filter) {
        String q = query == null ? ""
            : query.trim()
                .toLowerCase(Locale.ROOT);
        List<Achievement> source = q.isEmpty() ? pages.get(clampPage(page)).achievements : all;
        List<Achievement> out = new ArrayList<>();
        for (Achievement a : source) {
            if (isHidden(a)) continue;
            if (!filter.accepts(status(a))) continue;
            if (!q.isEmpty() && (isMasked(a) || !searchText.get(a)
                .contains(q))) continue;
            out.add(a);
        }
        return out;
    }

    public int clampPage(int page) {
        return Math.max(0, Math.min(page, pages.size() - 1));
    }

    public int countUnlocked(List<Achievement> list) {
        StatFileWriter s = stats();
        int n = 0;
        for (Achievement a : list) if (s.hasAchievementUnlocked(a)) n++;
        return n;
    }

    public int countUnlockedTotal() {
        return countUnlocked(all);
    }

    public int total() {
        return all.size();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static String strip(String s) {
        String r = EnumChatFormatting.getTextWithoutFormattingCodes(s);
        return r == null ? "" : r;
    }

    private interface StringSupplier {

        String get() throws Exception;
    }

    /** Some mods ship broken lang keys or formatters; one bad achievement must not break the whole screen. */
    private static String safe(StringSupplier s, String fallback) {
        try {
            String r = s.get();
            return r == null ? fallback : r;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
