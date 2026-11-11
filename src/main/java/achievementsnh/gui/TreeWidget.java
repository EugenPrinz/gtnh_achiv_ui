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
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import achievementsnh.data.AchievementIndex;
import achievementsnh.data.Filter;
import achievementsnh.data.Status;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Vanilla-style achievement tree for one page: nodes at {@code displayColumn/displayRow}, L-shaped links to the parent
 * (same routing as vanilla GuiAchievements), wheel zoom around the cursor, drag to pan, click to select.
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

    // per-page cache
    private int cachedPage = -1;
    private final List<Achievement> nodes = new ArrayList<>();
    private final Map<Achievement, Integer> slot = new IdentityHashMap<>();

    // per-frame caches, indexed like `nodes`
    private Status[] status = new Status[0];
    private boolean[] hidden = new boolean[0];
    private boolean[] match = new boolean[0];
    private boolean[] onScreen = new boolean[0];

    private static final RenderItem ITEM_RENDER = new RenderItem();

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
        pendingCenter = null;
    }

    /** Center the view on an achievement on next draw (keeps zoom unless it is too small to read). */
    public void centerOn(Achievement a) {
        pendingCenter = a;
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    private void refreshNodes() {
        int page = ui.currentPage();
        if (page == cachedPage) return;
        cachedPage = page;
        nodes.clear();
        slot.clear();
        for (Achievement a : index.pages()
            .get(page).achievements) {
            slot.put(a, nodes.size());
            nodes.add(a);
        }
        int n = nodes.size();
        status = new Status[n];
        hidden = new boolean[n];
        match = new boolean[n];
        onScreen = new boolean[n];
        needsFit = true;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        refreshNodes();
        int w = getArea().width, h = getArea().height;
        if (w <= 0 || h <= 0) return;
        updateCaches(w, h);
        applyPendingView(w, h);
        updateDragAndZoom(context);
        updateVisibility(w, h);

        mouseLocalX = context.getMouseX();
        mouseLocalY = context.getMouseY();
        boolean mouseInside = mouseLocalX >= 0 && mouseLocalY >= 0 && mouseLocalX < w && mouseLocalY < h;
        hovered = mouseInside && !dragged ? nodeAt(mouseLocalX, mouseLocalY) : null;

        int n = nodes.size();
        Stencil.applyAtZero(getArea(), context);
        GL11.glPushMatrix();
        GL11.glTranslatef(panX, panY, 0);
        GL11.glScalef(zoom, zoom, 1);

        // 1. links, under the nodes; vanilla routing: along the child's row to the parent's column, then up
        for (int i = 0; i < n; i++) {
            Achievement a = nodes.get(i);
            Integer pi = a.parentAchievement == null ? null : slot.get(a.parentAchievement);
            if (pi == null || hidden[i] || hidden[pi]) continue;
            if (!onScreen[i] && !onScreen[pi] && !lineOnScreen(a, a.parentAchievement, w, h)) continue;
            int color = lineColor(status[i]);
            int cx = a.displayColumn * CELL + HALF, cy = a.displayRow * CELL + HALF;
            int px = a.parentAchievement.displayColumn * CELL + HALF, py = a.parentAchievement.displayRow * CELL + HALF;
            GuiDraw.drawRect(Math.min(cx, px) - 1, cy - 1, Math.abs(cx - px) + 2, 2, color);
            GuiDraw.drawRect(px - 1, Math.min(cy, py) - 1, 2, Math.abs(cy - py) + 2, color);
        }

        // 2. frames
        for (int i = 0; i < n; i++) {
            if (!onScreen[i]) continue;
            Achievement a = nodes.get(i);
            int x = a.displayColumn * CELL, y = a.displayRow * CELL;
            if (a.getSpecial()) GuiDraw.drawRect(x - 1, y - 1, NODE + 2, NODE + 2, SPECIAL_RING);
            GuiDraw.drawRect(x, y, NODE, NODE, status[i] == Status.LOCKED ? LOCKED_BORDER : status[i].color);
            GuiDraw.drawRect(x + 2, y + 2, NODE - 4, NODE - 4, NODE_FILL);
        }

        // 3. items, one GL setup for the whole batch; skipped when too small to read
        if (zoom * 16f >= 6f) drawItems(n);

        // 4. overlays above items (items render at zLevel ~100 with depth test on)
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 300);
        Achievement selected = ui.selected();
        for (int i = 0; i < n; i++) {
            if (!onScreen[i]) continue;
            Achievement a = nodes.get(i);
            int x = a.displayColumn * CELL, y = a.displayRow * CELL;
            if (status[i] == Status.LOCKED) GuiDraw.drawRect(x + 2, y + 2, NODE - 4, NODE - 4, LOCKED_DIM);
            if (!match[i]) GuiDraw.drawRect(x, y, NODE, NODE, NO_MATCH_DIM);
            if (a == hovered) GuiDraw.drawRect(x, y, NODE, NODE, 0x30FFFFFF);
        }
        // selection ring last, inside the 2px gap between cells so it never covers neighbours
        Integer si = selected == null ? null : slot.get(selected);
        if (si != null && onScreen[si]) {
            int x = selected.displayColumn * CELL - 1, y = selected.displayRow * CELL - 1, s = NODE + 2;
            GuiDraw.drawRect(x, y, s, 2, SELECTED_RING);
            GuiDraw.drawRect(x, y + s - 2, s, 2, SELECTED_RING);
            GuiDraw.drawRect(x, y + 2, 2, s - 4, SELECTED_RING);
            GuiDraw.drawRect(x + s - 2, y + 2, 2, s - 4, SELECTED_RING);
        }
        GL11.glPopMatrix();

        GL11.glPopMatrix();
        Stencil.remove();
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

        if (pressed && Mouse.isButtonDown(0)) {
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

    /** Status, spoiler state and search/filter match: computed once per frame instead of per pass. */
    private void updateCaches(int w, int h) {
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
            Achievement a = nodes.get(i);
            float sx = a.displayColumn * CELL * zoom + panX, sy = a.displayRow * CELL * zoom + panY;
            onScreen[i] = sx + cell >= 0 && sy + cell >= 0 && sx <= w && sy <= h;
        }
    }

    private boolean lineOnScreen(Achievement a, Achievement p, int w, int h) {
        float x0 = (Math.min(a.displayColumn, p.displayColumn) * CELL + HALF) * zoom + panX;
        float x1 = (Math.max(a.displayColumn, p.displayColumn) * CELL + HALF) * zoom + panX;
        float y0 = (Math.min(a.displayRow, p.displayRow) * CELL + HALF) * zoom + panY;
        float y1 = (Math.max(a.displayRow, p.displayRow) * CELL + HALF) * zoom + panY;
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
                ITEM_RENDER.renderItemAndEffectIntoGUI(
                    mc.fontRenderer,
                    mc.getTextureManager(),
                    stack,
                    a.displayColumn * CELL + 3,
                    a.displayRow * CELL + 3);
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

    private static int lineColor(Status s) {
        return s == Status.LOCKED ? LOCKED_LINE : s.color;
    }

    private void applyPendingView(int w, int h) {
        if (needsFit) {
            needsFit = false;
            fit(w, h);
        }
        if (pendingCenter != null) {
            Achievement a = pendingCenter;
            pendingCenter = null;
            if (slot.get(a) != null) {
                if (zoom < 0.75f) zoom = 1f;
                targetZoom = zoom;
                panX = w / 2f - (a.displayColumn * CELL + HALF) * zoom;
                panY = h / 2f - (a.displayRow * CELL + HALF) * zoom;
            }
        }
    }

    private void fit(int w, int h) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            if (hidden[i]) continue;
            Achievement a = nodes.get(i);
            minX = Math.min(minX, a.displayColumn * CELL);
            minY = Math.min(minY, a.displayRow * CELL);
            maxX = Math.max(maxX, a.displayColumn * CELL + NODE);
            maxY = Math.max(maxY, a.displayRow * CELL + NODE);
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
        float wx = (localX - panX) / zoom, wy = (localY - panY) / zoom;
        // iterate backwards: later nodes are drawn on top
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (!onScreen[i]) continue;
            Achievement a = nodes.get(i);
            int x = a.displayColumn * CELL, y = a.displayRow * CELL;
            if (wx >= x && wx < x + NODE && wy >= y && wy < y + NODE) return a;
        }
        return null;
    }

    private void buildTooltip(com.cleanroommc.modularui.screen.RichTooltip tooltip) {
        Achievement a = hovered;
        if (a == null) return;
        Status s = AchievementIndex.status(a);
        tooltip.addLine(index.name(a));
        tooltip.addLine(EnumChatFormatting.GRAY + I18n.format(s.langKey));
        String desc = index.description(a);
        if (!desc.isEmpty()) tooltip.addLine(EnumChatFormatting.DARK_GRAY + desc);
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    @Override
    public Result onMousePressed(int mouseButton) {
        if (mouseButton != 0) return Result.IGNORE;
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

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
