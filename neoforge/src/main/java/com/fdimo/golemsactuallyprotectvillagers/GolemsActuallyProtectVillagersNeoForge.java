package com.fdimo.golemsactuallyprotectvillagers;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class GolemsActuallyProtectVillagersNeoForge {

    public GolemsActuallyProtectVillagersNeoForge(IEventBus eventBus) {
        Constants.LOG.info("Hello NeoForge world!");
        CommonClass.init();
    }
}

