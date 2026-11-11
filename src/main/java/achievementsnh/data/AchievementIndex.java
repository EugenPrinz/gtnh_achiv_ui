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

        PageInfo(int index, String name, ItemStack icon, List<Achievement> achievements) {
            this.index = index;
            this.name = name;
            this.icon = icon;
            this.achievements = achievements;
        }
    }

    private final List<PageInfo> pages = new ArrayList<>();
    private final List<Achievement> all = new ArrayList<>();
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
    }

    private void addPage(String name, ItemStack icon, List<Achievement> list) {
        PageInfo info = new PageInfo(pages.size(), name, icon, Collections.unmodifiableList(list));
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
