package achievementsnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = AchievementsNH.MODID,
    name = "Achievements NH",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    dependencies = "required-after:modularui2;after:BetterAchievements")
public class AchievementsNH {

    public static final String MODID = "achievementsnh";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "achievementsnh.ClientProxy", serverSide = "achievementsnh.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }
}
