package achievementsnh.gui;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import com.cleanroommc.modularui.drawable.text.TextRenderer;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Title + smaller subtitle in one leaf widget, each trimmed to one line with "...". Full text goes to the tooltip
 * when trimmed.
 * <p>
 * Replaces a {@code Flow.column} of two text widgets: that nested layout failed MUI2's partial re-layout on every
 * list rebuild, and a failed layout is retried each frame (log spam, single-digit FPS). A leaf has nothing to lay
 * out, and its text can change without triggering any re-layout.
 */
@SideOnly(Side.CLIENT)
public class TwoLineText extends Widget<TwoLineText> {

    private static final float SUB_SCALE = 0.75f;
    private static final int LINE_H = 9;
    private static final int SUB_H = 7;

    private final Supplier<String> title;
    private final Supplier<String> subtitle;
    private final IntSupplier titleColor;
    private final int subtitleColor;

    public TwoLineText(Supplier<String> title, Supplier<String> subtitle, IntSupplier titleColor, int subtitleColor) {
        this.title = title;
        this.subtitle = subtitle;
        this.titleColor = titleColor;
        this.subtitleColor = subtitleColor;
        tooltipDynamic(t -> {
            int w = getArea().width;
            FontRenderer fr = font();
            String a = safe(title), b = safe(subtitle);
            boolean cutA = fr.getStringWidth(a) > w;
            boolean cutB = fr.getStringWidth(b) * SUB_SCALE > w;
            if (cutA || cutB) {
                t.addLine(a);
                if (!b.isEmpty()) t.addLine(b);
            }
        });
        tooltipAutoUpdate(true);
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        int w = getArea().width, h = getArea().height;
        if (w <= 4) return;
        FontRenderer fr = font();
        String b = safe(subtitle);
        int total = b.isEmpty() ? LINE_H : LINE_H + SUB_H;
        int y = Math.max(0, (h - total) / 2);

        TextRenderer r = TextRenderer.SHARED;
        r.setSimulate(false);
        r.setShadow(false);

        r.setScale(1f);
        r.setAlignment(Alignment.TopLeft, w + 1);
        r.setColor(titleColor.getAsInt());
        r.setPos(0, y);
        r.draw(trim(fr, safe(title), w));

        if (!b.isEmpty()) {
            r.setScale(SUB_SCALE);
            r.setAlignment(Alignment.TopLeft, w + 1);
            r.setColor(subtitleColor);
            r.setPos(0, y + LINE_H + 1);
            r.draw(trim(fr, b, (int) (w / SUB_SCALE)));
            r.setScale(1f);
        }
    }

    private static String trim(FontRenderer fr, String s, int maxWidth) {
        if (fr.getStringWidth(s) <= maxWidth) return s;
        int dots = fr.getStringWidth("...");
        return fr.trimStringToWidth(s, Math.max(0, maxWidth - dots)) + "...";
    }

    private static String safe(Supplier<String> s) {
        String v = s.get();
        return v == null ? "" : v;
    }

    private static FontRenderer font() {
        return Minecraft.getMinecraft().fontRenderer;
    }
}
