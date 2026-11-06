package com.fdimo.golemsactuallyprotectvillagers;

import com.fdimo.golemsactuallyprotectvillagers.platform.Services;

public class CommonClass {
    public static void init() {
        Config.load();
        Constants.initThreadPool();
        Constants.LOG.info("Initialized {} on platform {}", Constants.MOD_NAME, Services.PLATFORM.getPlatformName());
    }
}
