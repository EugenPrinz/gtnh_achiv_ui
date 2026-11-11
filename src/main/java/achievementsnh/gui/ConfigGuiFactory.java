package achievementsnh.gui;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;

import achievementsnh.AchievementsNH;
import achievementsnh.Config;
import cpw.mods.fml.client.IModGuiFactory;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** "Config" button in the mod list. Values apply on save via {@link achievementsnh.ConfigSyncHandler}. */
@SideOnly(Side.CLIENT)
public class ConfigGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraftInstance) {}

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return ConfigScreen.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null;
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }

    public static class ConfigScreen extends GuiConfig {

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public ConfigScreen(GuiScreen parent) {
            super(
                parent,
                new ConfigElement(Config.config.getCategory(Config.CAT)).getChildElements(),
                AchievementsNH.MODID,
                false,
                false,
                "Achievements NH");
        }
    }
}
