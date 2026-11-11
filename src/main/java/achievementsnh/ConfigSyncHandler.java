package achievementsnh;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Re-reads values after the in-game config screen saves. Registered on the FML bus. */
@SideOnly(Side.CLIENT)
public class ConfigSyncHandler {

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (AchievementsNH.MODID.equals(event.modID)) Config.sync();
    }
}
