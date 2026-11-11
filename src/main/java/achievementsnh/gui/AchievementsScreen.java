package achievementsnh.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.client.C16PacketClientStatus;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.GuiScreenWrapper;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;

import achievementsnh.AchievementsNH;
import achievementsnh.Config;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Thin ModularScreen shell. All state and widgets live in {@link AchievementsUI}: ModularScreen builds its panel
 * inside the super constructor, before subclass fields exist, so the UI object has to be created first.
 */
@SideOnly(Side.CLIENT)
public class AchievementsScreen extends ModularScreen {

    private final AchievementsUI ui;

    private AchievementsScreen(AchievementsUI ui) {
        super(AchievementsNH.MODID, ui.buildPanel());
        this.ui = ui;
        openParentOnClose(true); // "Done" / Esc go back to the pause menu, like vanilla
    }

    public static GuiScreen createWrapper() {
        requestStats();
        AchievementsScreen screen = new AchievementsScreen(new AchievementsUI());
        screen.getContext()
            .setSettings(new UISettings());
        return new GuiScreenWrapper(screen);
    }

    /** Same request vanilla GuiAchievements sends on open; the reply updates the client StatFileWriter. */
    private static void requestStats() {
        NetHandlerPlayClient net = Minecraft.getMinecraft()
            .getNetHandler();
        if (net != null) {
            net.addToSendQueue(new C16PacketClientStatus(C16PacketClientStatus.EnumState.REQUEST_STATS));
        }
    }

    /** Dark backdrop over the world so the surroundings do not distract; opacity from config. */
    @Override
    public void drawScreen() {
        int alpha = Math.round(Config.backgroundOpacity * 2.55f);
        if (alpha > 0) {
            GuiDraw.drawRect(
                0,
                0,
                getContext().getScreenArea().width,
                getContext().getScreenArea().height,
                (alpha << 24) | 0x101010);
        }
        super.drawScreen();
    }

    @Override
    public boolean onKeyPressed(char typedChar, int keyCode) {
        // hotkeys only while no text field is focused, so typing in search is not intercepted
        if (!getContext().isFocused() && ui.onKey(typedChar, keyCode, getContext())) return true;
        return super.onKeyPressed(typedChar, keyCode);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        ui.tick();
    }
}
