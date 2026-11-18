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

    default boolean golemsActuallyProtectVillagers$canClimbLedge() {
        return false;
    }

    default boolean golemsActuallyProtectVillagers$isMantling() {
        return false;
    }

    default float golemsActuallyProtectVillagers$getMantleArmAngle(float partialTick) {
        return 0.0F;
    }

    default void golemsActuallyProtectVillagers$resetLerpSteps() {}

    default boolean golemsActuallyProtectVillagers$isPursuingThreat() {
        return false;
    }
}
