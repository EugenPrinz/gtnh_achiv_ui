package achievementsnh.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.Achievement;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import achievementsnh.Config;
import achievementsnh.data.AchievementIndex;
import achievementsnh.data.AchievementIndex.PageInfo;
import achievementsnh.data.Filter;
import achievementsnh.data.Status;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Achievement tree for one page: nodes, L-shaped links to the parent (vanilla routing), wheel zoom around the cursor,
 * drag to pan, click to select, R/U on a node for NEI.
 * <p>
 * Node positions come from {@link #layout}: vanilla {@code displayColumn/displayRow} for real pages, a compact grid
 * for virtual pages (pinned) whose achievements come from different pages and would overlap.
 * <p>
 * Transform: {@code local = world * zoom + pan}. World units are vanilla pixels (one cell = 24).
 */
@SideOnly(Side.CLIENT)
public class TreeWidget extends Widget<TreeWidget> implements Interactable {

    private static final int CELL = 24;
    private static final int NODE = 22;
    private static final int HALF = NODE / 2;
    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 2f;
    private static final int DRAG_THRESHOLD = 3;

    private static final int NODE_FILL = 0xFFC6C6C6;
    private static final int LOCKED_BORDER = 0xFF8A8A8A;
    private static final int LOCKED_LINE = 0xFFA0A0A0;
    private static final int SPECIAL_RING = 0xFFE0A000;
    private static final int SELECTED_RING = 0xFFFFE040;
    private static final int LOCKED_DIM = 0x88C6C6C6;
    private static final int NO_MATCH_DIM = 0xC0B4B4B4;

    private static final RenderItem ITEM_RENDER = new RenderItem();

    private final AchievementIndex index;
    private final AchievementsUI ui;

    private float zoom = 1f, targetZoom = 1f;
    private float zoomAnchorX, zoomAnchorY;
    private float panX, panY;
    private float panAtPressX, panAtPressY;
    private long lastFrameNanos;
    private boolean needsFit = true;
    private Achievement pendingCenter;

    // input state
    private int pressAbsX, pressAbsY;
    private boolean pressed, dragged;
    private int mouseLocalX = -1, mouseLocalY = -1;
    private Achievement hovered;

    // per-page layout
    private int cachedPage = -1;
    private int fittedPage = -1;
    private boolean drawLinks;
    private final List<Achievement> nodes = new ArrayList<>();
    private final Map<Achievement, Integer> slot = new IdentityHashMap<>();
    private int[] wx = new int[0], wy = new int[0];
    private int[] parent = new int[0];

    // per-frame caches, indexed like `nodes`
    private Status[] status = new Status[0];
    private boolean[] hidden = new boolean[0];
    private boolean[] match = new boolean[0];
    private boolean[] onScreen = new boolean[0];

    public TreeWidget(AchievementIndex index, AchievementsUI ui) {
        this.index = index;
        this.ui = ui;
        tooltipDynamic(this::buildTooltip);
        tooltipAutoUpdate(true);
        tooltipShowUpTimer(2);
    }

    // ---- control from AchievementsUI ------------------------------------------------------------------------------

    /** Fit the whole page into view on next draw. */
    public void requestFit() {
        needsFit = true;
        fittedPage = ui.currentPage();
        pendingCenter = null;
    }

    /** Center the view on an achievement on next draw (keeps zoom unless it is too small to read). */
    public void centerOn(Achievement a) {
        pendingCenter = a;
    }

    /** Page contents changed (pinned page edited): rebuild layout on next draw. */
    public void invalidate() {
        cachedPage = -1;
    }

    // ---- layout ---------------------------------------------------------------------------------------------------

    private void refreshNodes() {
        int page = ui.currentPage();
        if (page == cachedPage) return;
        cachedPage = page;
        PageInfo info = index.pages()
            .get(page);
        nodes.clear();
        slot.clear();
        for (Achievement a : info.achievements) {
            slot.put(a, nodes.size());
            nodes.add(a);
        }
        int n = nodes.size();
        wx = new int[n];
        wy = new int[n];
        parent = new int[n];
        status = new Status[n];
        hidden = new boolean[n];
        match = new boolean[n];
        onScreen = new boolean[n];
        layout(info, ui.isAutoLayout(info));
        // invalidate() of the same page (pin added/removed) keeps the view
        if (page != fittedPage) {
            fittedPage = page;
            needsFit = true;
        }
    }

    /** World position of every node; -1 in {@link #parent} means no link is drawn. */
    private void layout(PageInfo info, boolean auto) {
        int n = nodes.size();
        drawLinks = !info.virtual;
        if (!info.virtual) {
            for (int i = 0; i < n; i++) {
                Achievement a = nodes.get(i);
                Integer p = a.parentAchievement == null ? null : slot.get(a.parentAchievement);
                parent[i] = p == null || p == i ? -1 : p;
            }
            if (auto) {
                autoLayout();
                return;
            }
        }
        if (info.virtual) {
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n * 1.6)));
            for (int i = 0; i < n; i++) {
                wx[i] = (i % cols) * CELL;
                wy[i] = (i / cols) * CELL;
                parent[i] = -1;
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            Achievement a = nodes.get(i);
            wx[i] = a.displayColumn * CELL;
            wy[i] = a.displayRow * CELL;
        }
    }

    /**
     * Layout from the parent links only, ignoring the mod's positions (GT5 places hundreds of achievements in a plain
     * grid). Trees grow to the right like a file tree: column = depth, each leaf gets its own row, a parent sits on
     * the row of its first child, so vanilla L-shaped links read naturally. Trees are stacked top to bottom; nodes
     * without parent and children go into a compact grid to the right.
     */
    private void autoLayout() {
        int n = nodes.size();
        List<List<Integer>> kids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) kids.add(new ArrayList<>());
        for (int i = 0; i < n; i++) if (parent[i] >= 0) kids.get(parent[i])
            .add(i);

        boolean[] placed = new boolean[n];
        int[] nextRow = { 0 };
        int maxDepth = 0;
        List<Integer> singles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (parent[i] >= 0) continue;
            if (kids.get(i)
                .isEmpty()) {
                singles.add(i);
                continue;
            }
            maxDepth = Math.max(maxDepth, placeSubtree(i, 0, kids, placed, nextRow));
            nextRow[0]++; // blank row between trees
        }
        // anything left (a parent cycle) goes with the singles
        for (int i = 0; i < n; i++) if (!placed[i] && !singles.contains(i)) {
            singles.add(i);
            parent[i] = -1;
        }

        int gridX = singles.size() == nodes.size() ? 0 : (maxDepth + 2) * CELL;
        int cols = Math.max(4, (int) Math.ceil(Math.sqrt(singles.size() * 1.6)));
        for (int k = 0; k < singles.size(); k++) {
            int i = singles.get(k);
            wx[i] = gridX + (k % cols) * CELL;
            wy[i] = (k / cols) * CELL;
            placed[i] = true;
        }
    }

    /** Places a subtree; returns its maximum depth. Iterative-safe against cycles via {@code placed}. */
    private int placeSubtree(int i, int depth, List<List<Integer>> kids, boolean[] placed, int[] nextRow) {
        placed[i] = true;
        wx[i] = depth * CELL;
        List<Integer> ch = kids.get(i);
        int maxDepth = depth;
        int firstRow = -1;
        for (int c : ch) {
            if (placed[c]) continue;
            if (firstRow < 0) firstRow = nextRow[0];
            maxDepth = Math.max(maxDepth, placeSubtree(c, depth + 1, kids, placed, nextRow));
        }
        if (firstRow < 0) {
            wy[i] = nextRow[0]++ * CELL;
        } else {
            wy[i] = firstRow * CELL;
        }
        return maxDepth;
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        refreshNodes();
        int w = getArea().width, h = getArea().height;
        if (w <= 0 || h <= 0) return;
        updateCaches();
        applyPendingView(w, h);
        updateDragAndZoom(context);
        updateVisibility(w, h);

        mouseLocalX = context.getMouseX();
        mouseLocalY = context.getMouseY();
        boolean mouseInside = mouseLocalX >= 0 && mouseLocalY >= 0 && mouseLocalX < w && mouseLocalY < h;
        hovered = mouseInside && !dragged && !minimapDrag && !inMinimap(mouseLocalX, mouseLocalY)
            ? nodeAt(mouseLocalX, mouseLocalY)
            : null;

        int n = nodes.size();
        Stencil.applyAtZero(getArea(), context);
        GL11.glPushMatrix();
        GL11.glTranslatef(panX, panY, 0);
        GL11.glScalef(zoom, zoom, 1);

        // 1. links under the nodes; vanilla routing: along the child's row to the parent's column, then up
        if (drawLinks) {
            for (int i = 0; i < n; i++) {
                int pi = parent[i];
                if (pi < 0 || hidden[i] || hidden[pi]) continue;
                if (!onScreen[i] && !onScreen[pi] && !lineOnScreen(i, pi, w, h)) continue;
                int color = lineColor(status[i]);
                int cx = wx[i] + HALF, cy = wy[i] + HALF;
                int px = wx[pi] + HALF, py = wy[pi] + HALF;
                GuiDraw.drawRect(Math.min(cx, px) - 1, cy - 1, Math.abs(cx - px) + 2, 2, color);
                GuiDraw.drawRect(px - 1, Math.min(cy, py) - 1, 2, Math.abs(cy - py) + 2, color);
                drawArrow(i, cx, cy, px, py, color);
            }
        }

        // 2. frames
        for (int i = 0; i < n; i++) {
            if (!onScreen[i]) continue;
            Achievement a = nodes.get(i);
            int x = wx[i], y = wy[i];
            if (a.getSpecial()) GuiDraw.drawRect(x - 1, y - 1, NODE + 2, NODE + 2, SPECIAL_RING);
            GuiDraw.drawRect(x, y, NODE, NODE, status[i] == Status.LOCKED ? LOCKED_BORDER : status[i].color);
            GuiDraw.drawRect(x + 2, y + 2, NODE - 4, NODE - 4, NODE_FILL);
        }

        // 3. items, one GL setup for the whole batch; skipped when too small to read
        if (zoom * 16f >= 6f) drawItems(n);

        // 4. overlays above items (items render at zLevel ~100 with depth test on)
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 300);
        for (int i = 0; i < n; i++) {
            if (!onScreen[i]) continue;
            int x = wx[i], y = wy[i];
            if (status[i] == Status.LOCKED) GuiDraw.drawRect(x + 2, y + 2, NODE - 4, NODE - 4, LOCKED_DIM);
            if (!match[i]) GuiDraw.drawRect(x, y, NODE, NODE, NO_MATCH_DIM);
            if (nodes.get(i) == hovered) GuiDraw.drawRect(x, y, NODE, NODE, 0x30FFFFFF);
        }
        // selection ring last, inside the 2px gap between cells so it never covers neighbours
        Achievement selected = ui.selected();
        Integer si = selected == null ? null : slot.get(selected);
        if (si != null && onScreen[si]) {
            int x = wx[si] - 1, y = wy[si] - 1, s = NODE + 2;
            GuiDraw.drawRect(x, y, s, 2, SELECTED_RING);
            GuiDraw.drawRect(x, y + s - 2, s, 2, SELECTED_RING);
            GuiDraw.drawRect(x, y + 2, 2, s - 4, SELECTED_RING);
            GuiDraw.drawRect(x + s - 2, y + 2, 2, s - 4, SELECTED_RING);
        }
        GL11.glPopMatrix();

        GL11.glPopMatrix();
        drawMinimap(w, h);
        Stencil.remove();
    }

    // ---- minimap --------------------------------------------------------------------------------------------------

    private static final int MM_MAX_W = 96, MM_MAX_H = 72, MM_PAD = 2, MM_MARGIN = 4;
    private boolean minimapShown, minimapDrag;
    private int mmX, mmY, mmW, mmH, mmMinX, mmMinY;
    private float mmScale;

    /** Overview in the bottom-right corner, only when the page does not fit; click or drag on it to move the view. */
    private void drawMinimap(int w, int h) {
        minimapShown = false;
        if (!Config.showMinimap) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            if (hidden[i]) continue;
            minX = Math.min(minX, wx[i]);
            minY = Math.min(minY, wy[i]);
            maxX = Math.max(maxX, wx[i] + NODE);
            maxY = Math.max(maxY, wy[i] + NODE);
        }
        if (minX == Integer.MAX_VALUE) return;
        float bw = maxX - minX, bh = maxY - minY;
        boolean fits = minX * zoom + panX >= 0 && minY * zoom + panY >= 0
            && maxX * zoom + panX <= w
            && maxY * zoom + panY <= h;
        if (fits || w < MM_MAX_W * 2 || h < MM_MAX_H * 2) return;

        mmScale = Math.min((MM_MAX_W - 2 * MM_PAD) / bw, (MM_MAX_H - 2 * MM_PAD) / bh);
        mmW = Math.round(bw * mmScale) + 2 * MM_PAD;
        mmH = Math.round(bh * mmScale) + 2 * MM_PAD;
        mmX = w - mmW - MM_MARGIN;
        mmY = h - mmH - MM_MARGIN;
        mmMinX = minX;
        mmMinY = minY;
        minimapShown = true;

        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 400);
        GuiDraw.drawRect(mmX - 1, mmY - 1, mmW + 2, mmH + 2, 0xFF555555);
        GuiDraw.drawRect(mmX, mmY, mmW, mmH, 0xE0202020);
        float dot = Math.max(1f, NODE * mmScale);
        for (int i = 0; i < nodes.size(); i++) {
            if (hidden[i]) continue;
            int c = status[i] == Status.LOCKED ? 0xFF808080 : status[i].color;
            if (!match[i]) c = 0xFF4A4A4A;
            GuiDraw.drawRect(mmX + MM_PAD + (wx[i] - minX) * mmScale, mmY + MM_PAD + (wy[i] - minY) * mmScale, dot, dot, c);
        }
        // current view
        float vx0 = (-panX / zoom - minX) * mmScale, vy0 = (-panY / zoom - minY) * mmScale;
        float vw = w / zoom * mmScale, vh = h / zoom * mmScale;
        float x0 = Math.max(0, vx0), y0 = Math.max(0, vy0);
        float x1 = Math.min(mmW - 2 * MM_PAD, vx0 + vw), y1 = Math.min(mmH - 2 * MM_PAD, vy0 + vh);
        if (x1 > x0 && y1 > y0) {
            float bx = mmX + MM_PAD + x0, by = mmY + MM_PAD + y0, bwv = x1 - x0, bhv = y1 - y0;
            GuiDraw.drawRect(bx, by, bwv, 1, 0xFFFFFFFF);
            GuiDraw.drawRect(bx, by + bhv - 1, bwv, 1, 0xFFFFFFFF);
            GuiDraw.drawRect(bx, by, 1, bhv, 0xFFFFFFFF);
            GuiDraw.drawRect(bx + bwv - 1, by, 1, bhv, 0xFFFFFFFF);
        }
        GL11.glPopMatrix();
    }

    private boolean inMinimap(int lx, int ly) {
        return minimapShown && lx >= mmX && ly >= mmY && lx < mmX + mmW && ly < mmY + mmH;
    }

    /** Centers the view on the world point under a minimap position. */
    private void centerFromMinimap(int lx, int ly, int w, int h) {
        float worldX = mmMinX + (lx - mmX - MM_PAD) / mmScale;
        float worldY = mmMinY + (ly - mmY - MM_PAD) / mmScale;
        panX = w / 2f - worldX * zoom;
        panY = h / 2f - worldY * zoom;
    }

    /** Arrowhead where the link meets the child, pointing at the child (vanilla style). */
    private void drawArrow(int i, int cx, int cy, int px, int py, int color) {
        if (px < cx) { // link arrives horizontally from the left
            int tx = wx[i] - 1;
            for (int k = 0; k < 4; k++) GuiDraw.drawRect(tx - k, cy - k, 1, 2 * k + 1, color);
        } else if (px > cx) { // from the right
            int tx = wx[i] + NODE;
            for (int k = 0; k < 4; k++) GuiDraw.drawRect(tx + k, cy - k, 1, 2 * k + 1, color);
        } else if (py < cy) { // vertical, parent above
            int ty = wy[i] - 1;
            for (int k = 0; k < 4; k++) GuiDraw.drawRect(cx - k, ty - k, 2 * k + 1, 1, color);
        } else if (py > cy) { // vertical, parent below
            int ty = wy[i] + NODE;
            for (int k = 0; k < 4; k++) GuiDraw.drawRect(cx - k, ty + k, 2 * k + 1, 1, color);
        }
    }

    private static int lineColor(Status s) {
        return s == Status.LOCKED ? LOCKED_LINE : s.color;
    }

    /** Status, spoiler state and search/filter match: computed once per frame instead of per pass. */
    private void updateCaches() {
        Filter f = ui.filter();
        String q = AchievementIndex.normalizeQuery(ui.query());
        for (int i = 0; i < nodes.size(); i++) {
            Achievement a = nodes.get(i);
            Status st = AchievementIndex.status(a);
            status[i] = st;
            hidden[i] = index.isHidden(a);
            match[i] = f.accepts(st) && index.matchesNormalized(a, q);
        }
    }

    private void updateVisibility(int w, int h) {
        float cell = NODE * zoom;
        for (int i = 0; i < nodes.size(); i++) {
            if (hidden[i]) {
                onScreen[i] = false;
                continue;
            }
            float sx = wx[i] * zoom + panX, sy = wy[i] * zoom + panY;
            onScreen[i] = sx + cell >= 0 && sy + cell >= 0 && sx <= w && sy <= h;
        }
    }

    private boolean lineOnScreen(int i, int pi, int w, int h) {
        float x0 = (Math.min(wx[i], wx[pi]) + HALF) * zoom + panX;
        float x1 = (Math.max(wx[i], wx[pi]) + HALF) * zoom + panX;
        float y0 = (Math.min(wy[i], wy[pi]) + HALF) * zoom + panY;
        float y1 = (Math.max(wy[i], wy[pi]) + HALF) * zoom + panY;
        return x1 >= 0 && y1 >= 0 && x0 <= w && y0 <= h;
    }

    private void drawItems(int n) {
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        ITEM_RENDER.zLevel = 100f;
        for (int i = 0; i < n; i++) {
            if (!onScreen[i]) continue;
            Achievement a = nodes.get(i);
            ItemStack stack = a.theItemStack;
            if (stack == null || stack.getItem() == null || index.isMasked(a)) continue;
            try {
                ITEM_RENDER
                    .renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, wx[i] + 3, wy[i] + 3);
            } catch (Throwable ignored) {
                // a broken item renderer must not take the whole screen down
            }
        }
        ITEM_RENDER.zLevel = 0f;
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * GUI input in 1.7.10 is processed in runTick (20 Hz), so moving the view from onMouseDrag/onMouseScroll looks
     * like 20 fps. Here the view follows the cursor every frame; the input callbacks only start/stop the drag and set
     * the zoom target.
     */
    private void updateDragAndZoom(ModularGuiContext context) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;

        if (minimapDrag && Mouse.isButtonDown(0)) {
            centerFromMinimap(context.getMouseX(), context.getMouseY(), getArea().width, getArea().height);
        } else if (pressed && Mouse.isButtonDown(0)) {
            int dx = context.getAbsMouseX() - pressAbsX, dy = context.getAbsMouseY() - pressAbsY;
            if (!dragged && Math.abs(dx) + Math.abs(dy) >= DRAG_THRESHOLD) dragged = true;
            if (dragged) {
                panX = panAtPressX + dx;
                panY = panAtPressY + dy;
            }
        }

        if (zoom != targetZoom) {
            // exponential approach, ~120 ms to settle
            float t = Math.min(1f, dt * 18f);
            float next = zoom + (targetZoom - zoom) * t;
            if (Math.abs(next - targetZoom) < 0.002f) next = targetZoom;
            panX = zoomAnchorX - (zoomAnchorX - panX) * next / zoom;
            panY = zoomAnchorY - (zoomAnchorY - panY) * next / zoom;
            zoom = next;
        }
    }

    private void applyPendingView(int w, int h) {
        if (needsFit) {
            needsFit = false;
            fit(w, h);
        }
        if (pendingCenter != null) {
            Integer i = slot.get(pendingCenter);
            pendingCenter = null;
            if (i != null) {
                if (zoom < 0.75f) zoom = 1f;
                targetZoom = zoom;
                panX = w / 2f - (wx[i] + HALF) * zoom;
                panY = h / 2f - (wy[i] + HALF) * zoom;
            }
        }
    }

    private void fit(int w, int h) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            if (hidden[i]) continue;
            minX = Math.min(minX, wx[i]);
            minY = Math.min(minY, wy[i]);
            maxX = Math.max(maxX, wx[i] + NODE);
            maxY = Math.max(maxY, wy[i] + NODE);
        }
        if (minX == Integer.MAX_VALUE) {
            zoom = targetZoom = 1f;
            panX = panY = 0;
            return;
        }
        float bw = maxX - minX, bh = maxY - minY;
        zoom = targetZoom = clamp(Math.min((w - 16) / bw, (h - 16) / bh), MIN_ZOOM, 1f);
        panX = w / 2f - (minX + bw / 2f) * zoom;
        panY = h / 2f - (minY + bh / 2f) * zoom;
    }

    private Achievement nodeAt(int localX, int localY) {
        float px = (localX - panX) / zoom, py = (localY - panY) / zoom;
        // iterate backwards: later nodes are drawn on top
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (!onScreen[i]) continue;
            if (px >= wx[i] && px < wx[i] + NODE && py >= wy[i] && py < wy[i] + NODE) return nodes.get(i);
        }
        return null;
    }

    private void buildTooltip(RichTooltip tooltip) {
        Achievement a = hovered;
        if (a == null) return;
        Status s = AchievementIndex.status(a);
        tooltip.addLine((index.isPinned(a) ? "★ " : "") + index.name(a));
        tooltip.addLine(EnumChatFormatting.GRAY + I18n.format(s.langKey));
        String desc = index.description(a);
        if (!desc.isEmpty()) tooltip.addLine(EnumChatFormatting.DARK_GRAY + desc);
        if (NeiCompat.isLoaded()) tooltip.addLine(EnumChatFormatting.DARK_GRAY + I18n.format("achievementsnh.nei.hint"));
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    @Override
    public Result onMousePressed(int mouseButton) {
        if (mouseButton != 0) return Result.IGNORE;
        if (inMinimap(mouseLocalX, mouseLocalY)) {
            minimapDrag = true;
            centerFromMinimap(mouseLocalX, mouseLocalY, getArea().width, getArea().height);
            return Result.SUCCESS;
        }
        pressed = true;
        dragged = false;
        pressAbsX = getContext().getAbsMouseX();
        pressAbsY = getContext().getAbsMouseY();
        panAtPressX = panX;
        panAtPressY = panY;
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseRelease(int mouseButton) {
        if (minimapDrag) {
            minimapDrag = false;
            return true;
        }
        if (!pressed) return false;
        pressed = false;
        if (!dragged && hovered != null) {
            Interactable.playButtonClickSound();
            ui.select(hovered);
        }
        dragged = false;
        return true;
    }

    @Override
    public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
        float factor = scrollDirection.isUp() ? 1.25f : 0.8f;
        targetZoom = clamp(targetZoom * factor, MIN_ZOOM, MAX_ZOOM);
        // zoom around the cursor (position from the last drawn frame)
        zoomAnchorX = mouseLocalX >= 0 ? mouseLocalX : getArea().width / 2f;
        zoomAnchorY = mouseLocalY >= 0 ? mouseLocalY : getArea().height / 2f;
        return true;
    }

    /** R / U over a node: NEI recipe / usage of its item. */
    @Override
    public Result onKeyPressed(char typedChar, int keyCode) {
        Achievement a = hovered;
        if (a != null && NeiCompat.handleKey(keyCode, a.theItemStack)) return Result.SUCCESS;
        return Result.IGNORE;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
