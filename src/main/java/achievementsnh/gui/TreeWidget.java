package achievementsnh.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.resources.I18n;
import net.minecraft.stats.Achievement;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import achievementsnh.data.AchievementIndex;
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

    private float zoom = 1f;
    private float panX, panY;
    private boolean needsFit = true;
    private Achievement pendingCenter;

    // input state
    private int pressAbsX, pressAbsY, lastAbsX, lastAbsY;
    private boolean pressed, dragged;
    private int mouseLocalX = -1, mouseLocalY = -1;
    private Achievement hovered;

    // per-page cache
    private int cachedPage = -1;
    private final List<Achievement> nodes = new ArrayList<>();
    private final Map<Achievement, Boolean> onPage = new IdentityHashMap<>();

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
        onPage.clear();
        for (Achievement a : index.pages()
            .get(page).achievements) {
            nodes.add(a);
            onPage.put(a, Boolean.TRUE);
        }
        needsFit = true;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        refreshNodes();
        int w = getArea().width, h = getArea().height;
        if (w <= 0 || h <= 0) return;
        applyPendingView(w, h);

        mouseLocalX = context.getMouseX();
        mouseLocalY = context.getMouseY();
        boolean mouseInside = mouseLocalX >= 0 && mouseLocalY >= 0 && mouseLocalX < w && mouseLocalY < h;
        hovered = mouseInside && !dragged ? nodeAt(mouseLocalX, mouseLocalY) : null;

        Stencil.applyAtZero(getArea(), context);
        GL11.glPushMatrix();
        GL11.glTranslatef(panX, panY, 0);
        GL11.glScalef(zoom, zoom, 1);

        // links first, under the nodes
        for (Achievement a : nodes) {
            Achievement p = a.parentAchievement;
            if (p == null || onPage.get(p) == null || index.isHidden(a) || index.isHidden(p)) continue;
            int color = lineColor(AchievementIndex.status(a));
            int cx = a.displayColumn * CELL + HALF, cy = a.displayRow * CELL + HALF;
            int px = p.displayColumn * CELL + HALF, py = p.displayRow * CELL + HALF;
            // vanilla routing: along the child's row to the parent's column, then along that column to the parent
            GuiDraw.drawRect(Math.min(cx, px) - 1, cy - 1, Math.abs(cx - px) + 2, 2, color);
            GuiDraw.drawRect(px - 1, Math.min(cy, py) - 1, 2, Math.abs(cy - py) + 2, color);
        }

        Achievement selected = ui.selected();
        for (Achievement a : nodes) {
            if (index.isHidden(a)) continue;
            Status status = AchievementIndex.status(a);
            int x = a.displayColumn * CELL, y = a.displayRow * CELL;
            if (a == selected) GuiDraw.drawRect(x - 3, y - 3, NODE + 6, NODE + 6, SELECTED_RING);
            if (a.getSpecial()) GuiDraw.drawRect(x - 1, y - 1, NODE + 2, NODE + 2, SPECIAL_RING);
            GuiDraw.drawRect(x, y, NODE, NODE, status == Status.LOCKED ? LOCKED_BORDER : status.color);
            GuiDraw.drawRect(x + 2, y + 2, NODE - 4, NODE - 4, NODE_FILL);
            if (!index.isMasked(a)) GuiDraw.drawItem(a.theItemStack, x + 3, y + 3, 16, 16, 0);
            // overlays above the item: items render at zLevel ~100 with depth test on
            GL11.glPushMatrix();
            GL11.glTranslatef(0, 0, 250);
            if (status == Status.LOCKED) GuiDraw.drawRect(x + 2, y + 2, NODE - 4, NODE - 4, LOCKED_DIM);
            if (!ui.matches(a)) GuiDraw.drawRect(x, y, NODE, NODE, NO_MATCH_DIM);
            if (a == hovered) GuiDraw.drawRect(x, y, NODE, NODE, 0x30FFFFFF);
            GL11.glPopMatrix();
        }

        GL11.glPopMatrix();
        Stencil.remove();
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
            if (onPage.get(a) != null) {
                if (zoom < 0.75f) zoom = 1f;
                panX = w / 2f - (a.displayColumn * CELL + HALF) * zoom;
                panY = h / 2f - (a.displayRow * CELL + HALF) * zoom;
            }
        }
    }

    private void fit(int w, int h) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Achievement a : nodes) {
            if (index.isHidden(a)) continue;
            minX = Math.min(minX, a.displayColumn * CELL);
            minY = Math.min(minY, a.displayRow * CELL);
            maxX = Math.max(maxX, a.displayColumn * CELL + NODE);
            maxY = Math.max(maxY, a.displayRow * CELL + NODE);
        }
        if (minX == Integer.MAX_VALUE) {
            zoom = 1f;
            panX = panY = 0;
            return;
        }
        float bw = maxX - minX, bh = maxY - minY;
        zoom = clamp(Math.min((w - 16) / bw, (h - 16) / bh), MIN_ZOOM, 1f);
        panX = w / 2f - (minX + bw / 2f) * zoom;
        panY = h / 2f - (minY + bh / 2f) * zoom;
    }

    private Achievement nodeAt(int localX, int localY) {
        float wx = (localX - panX) / zoom, wy = (localY - panY) / zoom;
        // iterate backwards: later nodes are drawn on top
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Achievement a = nodes.get(i);
            if (index.isHidden(a)) continue;
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
        pressAbsX = lastAbsX = getContext().getAbsMouseX();
        pressAbsY = lastAbsY = getContext().getAbsMouseY();
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(int mouseButton, long timeSinceClick) {
        if (!pressed) return;
        int ax = getContext().getAbsMouseX(), ay = getContext().getAbsMouseY();
        if (!dragged && Math.abs(ax - pressAbsX) + Math.abs(ay - pressAbsY) >= DRAG_THRESHOLD) dragged = true;
        if (dragged) {
            panX += ax - lastAbsX;
            panY += ay - lastAbsY;
        }
        lastAbsX = ax;
        lastAbsY = ay;
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
        float newZoom = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        if (newZoom == zoom) return true;
        // keep the world point under the cursor fixed
        float mx = mouseLocalX >= 0 ? mouseLocalX : getArea().width / 2f;
        float my = mouseLocalY >= 0 ? mouseLocalY : getArea().height / 2f;
        panX = mx - (mx - panX) * newZoom / zoom;
        panY = my - (my - panY) * newZoom / zoom;
        zoom = newZoom;
        return true;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
