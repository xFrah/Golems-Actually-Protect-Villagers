package com.fdimo.golemsactuallyprotectvillagers;

import net.minecraftforge.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class GolemsActuallyProtectVillagersForge {

    public GolemsActuallyProtectVillagersForge() {
        Constants.LOG.info("Hello Forge world!");
        CommonClass.init();
    }
}
