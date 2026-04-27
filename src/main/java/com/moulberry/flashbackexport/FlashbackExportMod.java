package com.moulberry.flashbackexport;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlashbackExportMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("flashback-world-export");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Flashback World Export initialized");
    }
}
