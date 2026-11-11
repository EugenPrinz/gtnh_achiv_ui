package achievementsnh;

import net.minecraftforge.common.MinecraftForge;

import achievementsnh.gui.GuiHook;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        Config.load(event.getSuggestedConfigurationFile());
        MinecraftForge.EVENT_BUS.register(new GuiHook());
    }
}
