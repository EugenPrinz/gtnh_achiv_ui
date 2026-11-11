package achievementsnh.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.stats.Achievement;
import net.minecraft.util.EnumChatFormatting;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.DynamicDrawable;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ScrollingTextWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import achievementsnh.Config;
import achievementsnh.data.AchievementIndex;
import achievementsnh.data.AchievementIndex.PageInfo;
import achievementsnh.data.Filter;
import achievementsnh.data.Sort;
import achievementsnh.data.Status;
import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.widget.Interactable;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Layout:
 *
 * <pre>
 * [ title / counter            ] [ search ] [all][available][unlocked][locked]
 * [ pages ] [ achievement list                     ] [ details ]
 * [ total progress                         ] [old screen] [done]
 * </pre>
 */
@SideOnly(Side.CLIENT)
public class AchievementsUI {

    // remembered between openings, like vanilla remembers the page
    private static int lastPage = 0;
    private static String lastQuery = "";
    private static Filter lastFilter = Filter.ALL;
    private static Achievement lastSelected;
    private static boolean lastTreeMode = false;
    private static Sort lastSort = Sort.DEFAULT;
    private static boolean lastHideCompleted = false;
    private static boolean lastPagesByProgress = false;

    private static final int TEXT = 0xFF404040;
    private static final int TEXT_DIM = 0xFF7A7A7A;
    private static final int ROW_H = 22;
    private static final int ROW_GAP = 1;
    private static final int MAX_LINKS = 12;

    private static final IDrawable ROW_BG = new Rectangle().color(0x14000000);
    private static final IDrawable ROW_HOVER = new Rectangle().color(0x2A000000);
    private static final IDrawable ROW_SELECTED = new Rectangle().color(0x502F6FD0);
    private static final IDrawable[] STRIPE = { new Rectangle().color(Status.UNLOCKED.color),
        new Rectangle().color(Status.AVAILABLE.color), new Rectangle().color(0xFFB0B0B0) };

    private final AchievementIndex index = new AchievementIndex();
    private final AchList pageList = new AchList();
    private final AchList list = new AchList();
    private final AchList details = new AchList();
    private final TreeWidget tree = new TreeWidget(index, this);
    private ModularPanel panel;

    private int page = index.clampPage(lastPage);
    private String query = lastQuery;
    private Filter filter = lastFilter;
    private Achievement selected = lastSelected;
    private boolean treeMode = lastTreeMode;
    private Sort sort = lastSort;
    private boolean hideCompleted = lastHideCompleted;
    private boolean pagesByProgress = lastPagesByProgress;
    private List<PageInfo> shownPages = Collections.emptyList();
    private TextFieldWidget searchField;
    private List<Achievement> visible = Collections.emptyList();

    private int lastUnlocked = -1;

    // frame counter of this screen, refreshed once per second
    private long fpsWindowStart = System.nanoTime();
    private int fpsFrames, fpsShown;
    private Achievement pendingScroll;
    /** Page list rebuild requested from inside a page button click; done on the next tick, not mid-click. */
    private boolean pendingPageRebuild;

    // ---- panel ----------------------------------------------------------------------------------------------------

    ModularPanel buildPanel() {
        panel = new ModularPanel("achievementsnh_main").widthRel(Config.panelWidthPercent / 100f)
            .heightRel(Config.panelHeightPercent / 100f)
            .padding(6);

        rebuildPageList();
        // explicit height: an expanded ListWidget inside a Flow.column collapsed to one row
        pageList.widthRel(1f)
            .heightRelOffset(1f, -16);
        list.expanded()
            .heightRel(1f)
            .setEnabledIf(w -> !treeMode);
        tree.expanded()
            .heightRel(1f)
            .background(ROW_BG)
            .setEnabledIf(w -> treeMode);
        details.width(Config.detailsWidth)
            .heightRel(1f)
            .padding(4)
            .background(ROW_BG);

        refreshList();
        rebuildDetails();
        if (selected != null) {
            pendingScroll = selected;
            tree.centerOn(selected);
        }

        panel.child(
            Flow.column()
                .full()
                .childPadding(4)
                .child(header())
                .child(
                    Flow.row()
                        .widthRel(1f)
                        .expanded()
                        .childPadding(4)
                        .collapseDisabledChild()
                        .child(sidebar())
                        .child(list)
                        .child(tree)
                        .child(details))
                .child(footer()));
        return panel;
    }

    private IWidget header() {
        Flow row = Flow.row()
            .widthRel(1f)
            .height(20)
            .childPadding(4)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER);
        row.child(
            label(this::titleText, TEXT, 1f, Alignment.CenterLeft).expanded()
                .heightRel(1f));
        searchField = new TextFieldWidget().value(new StringValue.Dynamic(() -> query, this::setQuery))
            .autoUpdateOnChange(true)
            .acceptsExpressions(false)
            .setMaxLength(64)
            .hintText(I18n.format("achievementsnh.gui.search"))
            .width(180)
            .height(18);
        row.child(searchField);
        int sortW = 0;
        for (Sort so : Sort.values()) sortW = Math.max(sortW, textWidth(I18n.format(so.langKey)));
        row.child(
            new ButtonWidget<>().width(sortW + 16)
                .height(18)
                .overlay(IKey.dynamic(() -> I18n.format(sort.langKey)))
                .addTooltipLine(I18n.format("achievementsnh.sort.tooltip"))
                .onMousePressed(b -> {
                    if (b != 0) return false;
                    setSort(sort.next());
                    return true;
                }));
        for (Filter f : Filter.values()) {
            int w = Minecraft.getMinecraft().fontRenderer.getStringWidth(I18n.format(f.langKey)) + 16;
            row.child(
                new ButtonWidget<>().width(w)
                    .height(18)
                    .overlay(
                        IKey.dynamic(
                            () -> (filter == f ? EnumChatFormatting.YELLOW.toString() : "") + I18n.format(f.langKey)))
                    .onMousePressed(b -> {
                        if (b != 0) return false;
                        setFilter(f);
                        return true;
                    }));
        }
        return row;
    }

    private IWidget footer() {
        String oldText = I18n.format("achievementsnh.gui.old_screen");
        String doneText = I18n.format("gui.done");
        int oldW = Minecraft.getMinecraft().fontRenderer.getStringWidth(oldText) + 16;
        return Flow.row()
            .widthRel(1f)
            .height(18)
            .childPadding(4)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER)
            .child(viewButton(false, "achievementsnh.view.list"))
            .child(viewButton(true, "achievementsnh.view.tree"))
            .child(
                label(this::totalText, TEXT, 1f, Alignment.CenterLeft).expanded()
                    .heightRel(1f))
            .childIf(
                Config.showFps,
                () -> label(this::fpsText, TEXT_DIM, 1f, Alignment.CenterRight).width(56)
                    .heightRel(1f))
            .child(
                new ButtonWidget<>().width(textWidth(I18n.format("achievementsnh.gui.next_available")) + 16)
                    .height(18)
                    .overlay(IKey.str(I18n.format("achievementsnh.gui.next_available")))
                    .addTooltipLine(I18n.format("achievementsnh.gui.next_available.tooltip"))
                    .onMousePressed(b -> {
                        if (b != 0) return false;
                        nextAvailable();
                        return true;
                    }))
            .child(
                new ButtonWidget<>().width(oldW)
                    .height(18)
                    .overlay(IKey.str(oldText))
                    .addTooltipLine(I18n.format("achievementsnh.gui.old_screen.tooltip"))
                    .onMousePressed(b -> {
                        if (b != 0) return false;
                        GuiHook.openOriginalScreen(
                            panel.getScreen()
                                .getContext()
                                .getParentScreen());
                        return true;
                    }))
            .child(
                new ButtonWidget<>().width(70)
                    .height(18)
                    .overlay(IKey.str(doneText))
                    .onMousePressed(b -> {
                        if (b != 0) return false;
                        panel.closeIfOpen();
                        return true;
                    }));
    }

    private IWidget viewButton(boolean tree, String langKey) {
        int w = Minecraft.getMinecraft().fontRenderer.getStringWidth(I18n.format(langKey)) + 12;
        return new ButtonWidget<>().width(w)
            .height(18)
            .overlay(
                IKey.dynamic(() -> (treeMode == tree ? EnumChatFormatting.YELLOW.toString() : "") + I18n.format(langKey)))
            .onMousePressed(b -> {
                if (b != 0) return false;
                setTreeMode(tree);
                return true;
            });
    }

    // ---- page list ------------------------------------------------------------------------------------------------

    private IWidget sidebar() {
        return Flow.column()
            .width(Config.sidebarWidth)
            .heightRel(1f)
            .childPadding(2)
            .child(
                Flow.row()
                    .widthRel(1f)
                    .height(14)
                    .childPadding(2)
                    .child(
                        pageToggle(
                            "achievementsnh.pages.hide_done",
                            () -> hideCompleted,
                            () -> {
                                hideCompleted = !hideCompleted;
                                lastHideCompleted = hideCompleted;
                                rebuildPageList();
                            }))
                    .child(
                        pageToggle(
                            "achievementsnh.pages.by_progress",
                            () -> pagesByProgress,
                            () -> {
                                pagesByProgress = !pagesByProgress;
                                lastPagesByProgress = pagesByProgress;
                                rebuildPageList();
                            })))
            .child(pageList);
    }

    private IWidget pageToggle(String langKey, BooleanSupplier on, Runnable toggle) {
        return new ButtonWidget<>().expanded()
            .heightRel(1f)
            .overlay(
                IKey.dynamic(() -> (on.getAsBoolean() ? EnumChatFormatting.YELLOW.toString() : "") + I18n.format(langKey)))
            .addTooltipLine(I18n.format(langKey + ".tooltip"))
            .onMousePressed(b -> {
                if (b != 0) return false;
                toggle.run();
                return true;
            });
    }

    /** Page order/visibility: empty pages always hidden; optionally hide 100% pages and sort by progress. */
    private void rebuildPageList() {
        List<PageInfo> pages = new ArrayList<>();
        for (PageInfo p : index.pages()) {
            if (p.achievements.isEmpty()) continue;
            if (hideCompleted && p.index != page && index.countUnlocked(p.achievements) == p.achievements.size()) {
                continue;
            }
            pages.add(p);
        }
        if (pagesByProgress) {
            // closest to completion first
            pages.sort((x, y) -> Double.compare(progress(y), progress(x)));
        }
        shownPages = pages;
        pageList.removeAll();
        for (PageInfo p : pages) pageList.child(pageButton(p));
    }

    private double progress(PageInfo p) {
        return p.achievements.isEmpty() ? 0 : (double) index.countUnlocked(p.achievements) / p.achievements.size();
    }

    private static int textWidth(String s) {
        return Minecraft.getMinecraft().fontRenderer.getStringWidth(s);
    }

    private IWidget pageButton(PageInfo p) {
        return new ButtonWidget<>().widthRel(1f)
            .height(ROW_H)
            .marginBottom(ROW_GAP)
            .background(new DynamicDrawable(() -> p.index == page && (treeMode || !searching()) ? ROW_SELECTED : ROW_BG))
            .hoverBackground(ROW_HOVER)
            .child(
                Flow.row()
                    .full()
                    .padding(3, 2)
                    .childPadding(4)
                    .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                    .child(
                        new ItemDrawable(p.icon).asWidget()
                            .size(16))
                    .child(
                        Flow.column()
                            .expanded()
                            .heightRel(1f)
                            .paddingTop(1)
                            .child(
                                new ScrollingTextWidget(IKey.str(p.name)).color(TEXT)
                                    .widthRel(1f)
                                    .height(9))
                            .child(
                                label(() -> progressText(p.achievements), TEXT_DIM, 0.75f, Alignment.CenterLeft)
                                    .widthRel(1f)
                                    .height(7))))
            .onMousePressed(b -> {
                if (b != 0) return false;
                selectPage(p.index);
                return true;
            });
    }

    // ---- achievement list -----------------------------------------------------------------------------------------

    private void refreshList() {
        list.removeAll();
        visible = index.query(page, query, filter);
        index.sort(visible, sort);
        boolean global = searching();
        int shown = Math.min(visible.size(), Config.maxRows);
        for (int i = 0; i < shown; i++) list.child(row(visible.get(i), global));
        if (visible.isEmpty()) {
            list.child(text(IKey.str(I18n.format("achievementsnh.gui.nothing_found")), TEXT_DIM, 1f));
        } else if (visible.size() > shown) {
            list.child(text(IKey.str(I18n.format("achievementsnh.gui.more", visible.size() - shown)), TEXT_DIM, 1f));
        }
        if (list.isValid()) list.getScrollData()
            .scrollTo(list.getScrollArea(), 0);
    }

    private IWidget row(Achievement a, boolean showPage) {
        Flow content = Flow.row()
            .full()
            .padding(0, 3, 2, 2)
            .childPadding(5)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER)
            .child(
                new DynamicDrawable(
                    () -> STRIPE[AchievementIndex.status(a)
                        .ordinal()]).asWidget()
                            .width(2)
                            .heightRel(1f))
            .child(
                new ItemDrawable(a.theItemStack).asWidget()
                    .size(16))
            .child(
                Flow.column()
                    .expanded()
                    .heightRel(1f)
                    .paddingTop(1)
                    .child(
                        new ScrollingTextWidget(IKey.str(index.name(a)))
                            .color(() -> AchievementIndex.status(a).color)
                            .widthRel(1f)
                            .height(9))
                    .child(
                        new ScrollingTextWidget(IKey.str(index.description(a))).color(TEXT_DIM)
                            .scale(0.75f)
                            .widthRel(1f)
                            .height(7)));
        if (showPage) {
            PageInfo p = index.pageOf(a);
            content.child(
                IKey.str(p == null ? "" : p.name)
                    .asWidget()
                    .color(TEXT_DIM)
                    .scale(0.75f)
                    .textAlign(Alignment.CenterRight)
                    .width(70)
                    .height(7));
        }
        return new ButtonWidget<>().widthRel(1f)
            .height(ROW_H)
            .marginBottom(ROW_GAP)
            .background(new DynamicDrawable(() -> a == selected ? ROW_SELECTED : ROW_BG))
            .hoverBackground(ROW_HOVER)
            .child(content)
            .onMousePressed(b -> {
                if (b != 0) return false;
                select(a);
                return true;
            });
    }

    // ---- details --------------------------------------------------------------------------------------------------

    private void rebuildDetails() {
        details.removeAll();
        Achievement a = selected;
        if (a == null || index.pageOf(a) == null || index.isHidden(a)) {
            details.child(text(IKey.str(I18n.format("achievementsnh.gui.select_hint")), TEXT_DIM, 1f));
            return;
        }
        Status status = AchievementIndex.status(a);
        PageInfo p = index.pageOf(a);

        details.child(
            new ItemDrawable(a.theItemStack).asWidget()
                .size(24)
                .marginBottom(3));
        details.child(text(IKey.str(index.name(a)), status.color, 1f));

        String statusLine = I18n.format(status.langKey);
        if (a.getSpecial()) statusLine += " · " + I18n.format("achievementsnh.gui.special");
        details.child(text(IKey.str(statusLine), status.color, 0.75f));
        details.child(text(IKey.str(I18n.format("achievementsnh.gui.page", p.name)), TEXT_DIM, 0.75f));

        String desc = index.description(a);
        if (!desc.isEmpty()) details.child(text(IKey.str(desc), TEXT, 1f).marginTop(3));

        if (a.parentAchievement != null && !index.isHidden(a.parentAchievement)) {
            details.child(text(IKey.str(I18n.format("achievementsnh.gui.requires")), TEXT_DIM, 0.75f).marginTop(5));
            details.child(link(a.parentAchievement));
        }

        int shown = 0, hidden = 0;
        for (Achievement c : index.childrenOf(a)) {
            if (index.isHidden(c)) continue;
            if (shown == 0) {
                details.child(text(IKey.str(I18n.format("achievementsnh.gui.unlocks")), TEXT_DIM, 0.75f).marginTop(5));
            }
            if (shown < MAX_LINKS) {
                details.child(link(c));
                shown++;
            } else {
                hidden++;
            }
        }
        if (hidden > 0) details.child(text(IKey.str(I18n.format("achievementsnh.gui.more", hidden)), TEXT_DIM, 0.75f));
    }

    private IWidget link(Achievement a) {
        return new ButtonWidget<>().widthRel(1f)
            .height(18)
            .marginBottom(ROW_GAP)
            .background(ROW_BG)
            .hoverBackground(ROW_HOVER)
            .child(
                Flow.row()
                    .full()
                    .padding(2, 1)
                    .childPadding(3)
                    .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                    .child(
                        new ItemDrawable(a.theItemStack).asWidget()
                            .size(14))
                    .child(
                        new ScrollingTextWidget(IKey.str(index.name(a)))
                            .color(() -> AchievementIndex.status(a).color)
                            .expanded()
                            .height(9)))
            .onMousePressed(b -> {
                if (b != 0) return false;
                navigateTo(a);
                return true;
            });
    }

    /**
     * Text that changes at runtime, drawn as an overlay instead of a TextWidget: a TextWidget re-runs layout of the
     * whole widget tree every time its text changes.
     */
    private static Widget<?> label(Supplier<String> text, int color, float scale, Alignment align) {
        return new Widget<>().overlay(
            IKey.dynamic(text)
                .alignment(align)
                .color(color)
                .scale(scale));
    }

    private static TextWidget<?> text(IKey key, int color, float scale) {
        return key.asWidget()
            .color(color)
            .scale(scale)
            .textAlign(Alignment.TopLeft)
            .widthRel(1f)
            .marginBottom(2);
    }

    // ---- state ----------------------------------------------------------------------------------------------------

    private boolean searching() {
        return !query.trim()
            .isEmpty();
    }

    private void setQuery(String q) {
        if (q == null) q = "";
        if (q.equals(query)) return;
        query = q;
        lastQuery = q;
        refreshList();
    }

    private void setFilter(Filter f) {
        if (f == filter) return;
        filter = f;
        lastFilter = f;
        refreshList();
    }

    private void setSort(Sort s) {
        sort = s;
        lastSort = s;
        refreshList();
    }

    // ---- keyboard -------------------------------------------------------------------------------------------------

    /**
     * Screen hotkeys, only while no text field has focus. Ctrl+F search, Tab list/tree, Up/Down page,
     * Enter show selected, N next available.
     */
    boolean onKey(char typedChar, int keyCode, ModularGuiContext context) {
        if (keyCode == Keyboard.KEY_F && Interactable.hasControlDown()) {
            if (searchField != null) context.focus(searchField);
            return true;
        }
        switch (keyCode) {
            case Keyboard.KEY_TAB -> setTreeMode(!treeMode);
            case Keyboard.KEY_UP -> movePage(-1);
            case Keyboard.KEY_DOWN -> movePage(1);
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> {
                if (selected != null) navigateTo(selected);
            }
            case Keyboard.KEY_N -> nextAvailable();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void movePage(int delta) {
        if (shownPages.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < shownPages.size(); i++) if (shownPages.get(i).index == page) at = i;
        int next = at < 0 ? 0 : Math.floorMod(at + delta, shownPages.size());
        selectPage(shownPages.get(next).index);
    }

    /**
     * Next achievement that can be unlocked right now: after the selection on the current page (in on-screen order),
     * then on the following pages.
     */
    private void nextAvailable() {
        // list: rows as shown (search, filter, sort applied); tree: the page in registration order
        List<Achievement> order = treeMode ? index.pages()
            .get(page).achievements : visible;
        int start = selected == null ? -1 : order.indexOf(selected);
        for (int k = 1; k <= order.size(); k++) {
            Achievement a = order.get(Math.floorMod(start + k, order.size()));
            if (isAvailable(a)) {
                navigateTo(a);
                return;
            }
        }
        List<PageInfo> pages = index.pages();
        for (int k = 1; k < pages.size(); k++) {
            PageInfo p = pages.get((page + k) % pages.size());
            for (Achievement a : p.achievements) {
                if (isAvailable(a)) {
                    navigateTo(a);
                    return;
                }
            }
        }
    }

    private boolean isAvailable(Achievement a) {
        return !index.isHidden(a) && AchievementIndex.status(a) == Status.AVAILABLE;
    }

    private void selectPage(int i) {
        if (hideCompleted && i != page) pendingPageRebuild = true;
        if (treeMode) {
            // tree keeps search/filter (they only dim nodes); clicking the current page re-fits the view
            if (i == page) tree.requestFit();
            page = i;
            lastPage = i;
            refreshList();
            return;
        }
        boolean changed = i != page || searching();
        page = i;
        lastPage = i;
        if (searching()) {
            query = "";
            lastQuery = "";
        }
        if (changed) refreshList();
    }

    void select(Achievement a) {
        selected = a;
        lastSelected = a;
        rebuildDetails();
    }

    /** Jump from the details panel to another achievement, switching page if needed. */
    private void navigateTo(Achievement a) {
        PageInfo p = index.pageOf(a);
        if (treeMode) {
            if (p != null && p.index != page) {
                page = p.index;
                lastPage = p.index;
                refreshList();
            }
            select(a);
            tree.centerOn(a);
            return;
        }
        if (!visible.contains(a)) {
            query = "";
            lastQuery = "";
            filter = Filter.ALL;
            lastFilter = Filter.ALL;
            if (p != null) {
                page = p.index;
                lastPage = p.index;
            }
            refreshList();
        }
        select(a);
        pendingScroll = a;
    }

    private void setTreeMode(boolean on) {
        if (treeMode == on) return;
        treeMode = on;
        lastTreeMode = on;
        if (on) {
            if (selected != null && index.pageOf(selected) != null && index.pageOf(selected).index == page) {
                tree.centerOn(selected);
            }
        } else {
            refreshList();
            if (selected != null) pendingScroll = selected;
        }
    }

    // ---- accessors for TreeWidget ---------------------------------------------------------------------------------

    int currentPage() {
        return page;
    }

    Achievement selected() {
        return selected;
    }

    Filter filter() {
        return filter;
    }

    String query() {
        return query;
    }

    /** Current search text and status filter; the tree dims nodes that do not match. */
    boolean matches(Achievement a) {
        return filter.accepts(AchievementIndex.status(a)) && index.matchesQuery(a, query);
    }

    /** Called every client tick. */
    void tick() {
        int unlocked = index.countUnlockedTotal();
        if (unlocked != lastUnlocked) {
            boolean first = lastUnlocked < 0;
            lastUnlocked = unlocked;
            // stats arrive asynchronously after REQUEST_STATS; membership only changes when filtering or hiding
            if (!first) {
                if (filter != Filter.ALL || sort == Sort.STATUS || Config.hideSpoilers) refreshList();
                rebuildDetails();
            }
            if (hideCompleted || pagesByProgress) pendingPageRebuild = true;
        }
        if (pendingPageRebuild) {
            pendingPageRebuild = false;
            rebuildPageList();
        }
        if (pendingScroll != null && list.isValid()) {
            int i = visible.indexOf(pendingScroll);
            if (i >= 0) {
                int y = i * (ROW_H + ROW_GAP) - list.getArea().height / 2 + ROW_H / 2;
                list.getScrollData()
                    .scrollTo(list.getScrollArea(), Math.max(0, y));
            }
            pendingScroll = null;
        }
    }

    // ---- texts ----------------------------------------------------------------------------------------------------

    private String titleText() {
        if (searching() && !treeMode) return I18n.format("achievementsnh.gui.found", visible.size());
        PageInfo p = index.pages()
            .get(page);
        String title = p.name + "  " + EnumChatFormatting.GRAY + progressText(p.achievements);
        if (treeMode && (searching() || filter != Filter.ALL)) {
            int n = 0;
            for (Achievement a : p.achievements) if (!index.isHidden(a) && matches(a)) n++;
            title += "  " + EnumChatFormatting.DARK_GRAY + I18n.format("achievementsnh.gui.found_page", n);
        }
        return title;
    }

    private String progressText(List<Achievement> list) {
        int total = list.size();
        int done = index.countUnlocked(list);
        int pct = total == 0 ? 0 : done * 100 / total;
        return done + "/" + total + " (" + pct + "%)";
    }

    /** Called once per drawn frame by the footer label. */
    private String fpsText() {
        fpsFrames++;
        long now = System.nanoTime();
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 1_000_000_000L) {
            fpsShown = (int) Math.round(fpsFrames * 1e9 / elapsed);
            fpsFrames = 0;
            fpsWindowStart = now;
        }
        return "FPS: " + fpsShown;
    }

    private String totalText() {
        int total = index.total();
        int done = index.countUnlockedTotal();
        int pct = total == 0 ? 0 : done * 100 / total;
        return I18n.format("achievementsnh.gui.total", done, total, pct);
    }
}
