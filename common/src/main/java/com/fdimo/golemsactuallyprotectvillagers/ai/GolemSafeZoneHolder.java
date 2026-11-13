package com.fdimo.golemsactuallyprotectvillagers.ai;

public interface GolemSafeZoneHolder {
    GolemSafeZone golemsActuallyProtectVillagers$getSafeZone();

    default boolean golemsActuallyProtectVillagers$isProtectingVillager() {
        return false;
    }

    default ProtectVillagersTargetGoal golemsActuallyProtectVillagers$getProtectGoal() {
        return null;
    }

    default void golemsActuallyProtectVillagers$setRescueFollowRangeBoost(boolean enabled) {}

    default boolean golemsActuallyProtectVillagers$canEnterHazard() {
        return false;
    }
}
