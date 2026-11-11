package achievementsnh.gui;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import achievementsnh.AchievementsNH;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * NEI recipe / usage lookup through reflection, so the mod neither needs NEI to load nor pins an NEI version.
 * API: {@code GuiCraftingRecipe.openRecipeGui("item", stack)} / {@code GuiUsageRecipe.openRecipeGui("item", stack)}.
 */
@SideOnly(Side.CLIENT)
public final class NeiCompat {

    private static boolean checked;
    private static Method recipe, usage;

    private NeiCompat() {}

    public static boolean isLoaded() {
        init();
        return recipe != null;
    }

    /** R = recipe, U = usage. Returns true if the key was handled. */
    public static boolean handleKey(int keyCode, ItemStack stack) {
        if (keyCode == Keyboard.KEY_R) return open(recipe, stack);
        if (keyCode == Keyboard.KEY_U) return open(usage, stack);
        return false;
    }

    private static boolean open(Method m, ItemStack stack) {
        init();
        if (m == null || stack == null || stack.getItem() == null) return false;
        try {
            return (Boolean) m.invoke(null, "item", new Object[] { stack.copy() });
        } catch (Throwable t) {
            AchievementsNH.LOG.warn("NEI lookup failed for {}", stack, t);
            return false;
        }
    }

    private static void init() {
        if (checked) return;
        checked = true;
        if (!Loader.isModLoaded("NotEnoughItems")) return;
        try {
            recipe = Class.forName("codechicken.nei.recipe.GuiCraftingRecipe")
                .getMethod("openRecipeGui", String.class, Object[].class);
            usage = Class.forName("codechicken.nei.recipe.GuiUsageRecipe")
                .getMethod("openRecipeGui", String.class, Object[].class);
        } catch (Throwable t) {
            recipe = usage = null;
            AchievementsNH.LOG.warn("NEI found but its recipe API is not accessible", t);
        }
    }
}
