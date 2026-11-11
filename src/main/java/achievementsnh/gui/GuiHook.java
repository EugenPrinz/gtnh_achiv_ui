package achievementsnh.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.achievement.GuiAchievements;
import net.minecraftforge.client.event.GuiOpenEvent;

import achievementsnh.AchievementsNH;
import achievementsnh.Config;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Swaps the achievements screen for ours.
 * <p>
 * Runs at HIGH priority, before Better Achievements (NORMAL), and replaces {@code event.gui} instead of cancelling,
 * so BA never sees a {@link GuiAchievements} and ModularUI2's LOWEST listener sees our wrapper as the new screen.
 */
@SideOnly(Side.CLIENT)
public class GuiHook {

    private static final String BA_SCREEN = "betterachievements.gui.GuiBetterAchievements";
    private static final String BA_OLD_SCREEN = "betterachievements.gui.GuiAchievementsOld";

    /** Set while we open the old screen on purpose, so it is not intercepted again. */
    private static boolean bypassOnce;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onGuiOpen(GuiOpenEvent event) {
        if (!Config.enabled || event.gui == null) return;
        if (bypassOnce) {
            bypassOnce = false;
            return;
        }
        String cls = event.gui.getClass().getName();
        boolean vanilla = event.gui instanceof GuiAchievements && !BA_OLD_SCREEN.equals(cls);
        if (!vanilla && !BA_SCREEN.equals(cls)) return;
        if (Minecraft.getMinecraft().thePlayer == null) return;
        try {
            event.gui = AchievementsScreen.createWrapper();
        } catch (Throwable t) {
            // never leave the player without an achievements screen
            AchievementsNH.LOG.error("Failed to build the achievements screen, falling back to the original one", t);
        }
    }

    /** Opens Better Achievements' screen if installed, otherwise vanilla, skipping our hook once. */
    public static void openOriginalScreen(GuiScreen parent) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = null;
        try {
            Class<?> c = Class.forName(BA_SCREEN);
            screen = (GuiScreen) c.getConstructor(GuiScreen.class, int.class)
                .newInstance(parent, 0);
        } catch (ClassNotFoundException ignored) {
            // Better Achievements not installed
        } catch (Throwable t) {
            AchievementsNH.LOG.warn("Could not open the Better Achievements screen", t);
        }
        if (screen == null) screen = new GuiAchievements(parent, mc.thePlayer.getStatFileWriter());
        bypassOnce = true;
        try {
            mc.displayGuiScreen(screen); // posts GuiOpenEvent synchronously
        } finally {
            bypassOnce = false;
        }
    }
}
