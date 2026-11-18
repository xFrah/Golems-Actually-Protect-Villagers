package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class DebugChat {

    private DebugChat() {}

    public static void send(ServerLevel serverLevel, String message) {
        if (Config.DATA == null || !Config.DATA.enableChatDebug || serverLevel == null) {
            return;
        }
        Component comp = Component.literal(message);
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            player.sendSystemMessage(comp);
        }
    }

    public static void send(ServerLevel serverLevel, String format, Object... args) {
        if (Config.DATA == null || !Config.DATA.enableChatDebug || serverLevel == null) {
            return;
        }
        send(serverLevel, String.format(format, args));
    }
}
