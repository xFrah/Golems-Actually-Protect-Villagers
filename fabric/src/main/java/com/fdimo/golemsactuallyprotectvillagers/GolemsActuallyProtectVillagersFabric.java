package com.fdimo.golemsactuallyprotectvillagers;

import net.fabricmc.api.ModInitializer;

public class GolemsActuallyProtectVillagersFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Constants.LOG.info("Hello Fabric world!");
        CommonClass.init();
    }
}

