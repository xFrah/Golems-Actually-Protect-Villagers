package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.ai.AsyncGolemPathfinder;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZone;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator extends NodeEvaluator {

    @Inject(method = "getNeighbors", at = @At("RETURN"), cancellable = true)
    private void onGetNeighbors(Node[] output, Node node, CallbackInfoReturnable<Integer> cir) {
        if (this.mob instanceof GolemSafeZoneHolder holder) {
            GolemSafeZone safeZone = holder.golemsActuallyProtectVillagers$getSafeZone();
            if (safeZone == null) {
                return;
            }

            boolean isProtecting = holder.golemsActuallyProtectVillagers$isProtectingVillager()
                || AsyncGolemPathfinder.EVALUATING_PROTECT_PATH.get();

            int count = cir.getReturnValue();
            int validCount = 0;
            for (int i = 0; i < count; i++) {
                Node neighbor = output[i];
                if (neighbor == null) continue;

                BlockPos pos = neighbor.asBlockPos();

                // 1. Confirmed hazard:
                if (safeZone.isConfirmedHazard(pos)) {
                    if (isProtecting && neighbor.costMalus >= 0.0F) {
                        // Railguards lowered when actively protecting an imperiled villager (+2.0F mild penalty)
                        neighbor.costMalus += 2.0F;
                        output[validCount++] = neighbor;
                    } else {
                        // Hard obstacle: completely impassable! Golem stays safe at the rim!
                        neighbor.costMalus = -1.0F;
                    }
                    continue;
                }

                // 2. Obstacle inflation / edge buffer: mild penalty to steer path toward center when options exist
                if (golemsActuallyProtectVillagers$isAdjacentToHazard(safeZone, pos)) {
                    if (neighbor.costMalus >= 0.0F) {
                        neighbor.costMalus += 0.5F;
                    }
                }

                output[validCount++] = neighbor;
            }
            cir.setReturnValue(validCount);
        }
    }

    @Unique
    private static boolean golemsActuallyProtectVillagers$isAdjacentToHazard(GolemSafeZone safeZone, BlockPos pos) {
        return safeZone.isConfirmedHazard(pos.north())
            || safeZone.isConfirmedHazard(pos.south())
            || safeZone.isConfirmedHazard(pos.east())
            || safeZone.isConfirmedHazard(pos.west())
            || safeZone.isConfirmedHazard(pos.north().below())
            || safeZone.isConfirmedHazard(pos.south().below())
            || safeZone.isConfirmedHazard(pos.east().below())
            || safeZone.isConfirmedHazard(pos.west().below());
    }
}
