package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.ai.AsyncGolemPathfinder;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZone;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator extends NodeEvaluator {

    @Unique
    private final BlockPos.MutableBlockPos golemsActuallyProtectVillagers$currentEvaluatingPos =
            new BlockPos.MutableBlockPos();

    @Unique
    private final BlockPos.MutableBlockPos golemsActuallyProtectVillagers$scratchNeighborPos =
            new BlockPos.MutableBlockPos();

    @Inject(method = "getNeighbors", at = @At("RETURN"), cancellable = true)
    private void onGetNeighbors(Node[] output, Node node, CallbackInfoReturnable<Integer> cir) {
        if (this.mob instanceof GolemSafeZoneHolder holder) {
            GolemSafeZone safeZone = holder.golemsActuallyProtectVillagers$getSafeZone();

            boolean canEnter = holder.golemsActuallyProtectVillagers$canEnterHazard();
            com.fdimo.golemsactuallyprotectvillagers.ai.ThreatCandidate candidate = AsyncGolemPathfinder.CURRENT_EVALUATING_CANDIDATE
                    .get();
            if (candidate != null && safeZone != null) {
                net.minecraft.world.entity.npc.villager.Villager villager = candidate.getImperiledVillager();
                net.minecraft.world.entity.Mob enemy = candidate.getMob();
                if (villager != null && enemy != null) {
                    canEnter = !safeZone.isSafe(villager.blockPosition()) && enemy.hasLineOfSight(villager);
                }
            }

            int count = cir.getReturnValue();
            int validCount = 0;
            for (int i = 0; i < count; i++) {
                Node neighbor = output[i];
                if (neighbor == null)
                    continue;

                int nx = neighbor.x;
                int ny = neighbor.y;
                int nz = neighbor.z;

                // 1. Confirmed hazard:
                if (safeZone != null && safeZone.isConfirmedHazard(nx, ny, nz)) {
                    if (canEnter && neighbor.costMalus >= 0.0F) {
                        // Railguards lowered ONLY when imperiled villager is actually trapped down in
                        // the hazard (+2.0F mild penalty)
                        neighbor.costMalus += 2.0F;
                        output[validCount++] = neighbor;
                    } else {
                        // Hard obstacle: completely impassable! Golem stays safe at the rim!
                        neighbor.costMalus = -1.0F;
                    }
                    continue;
                }

                // 2. Obstacle inflation / edge buffer: mild penalty to steer path toward center
                // when options exist
                if (safeZone != null && safeZone.isAdjacentToHazard(nx, ny, nz)) {
                    if (neighbor.costMalus >= 0.0F) {
                        neighbor.costMalus += 0.5F;
                    }
                }

                // 3. Obstacle inflation / fence buffer: penalize nodes adjacent to fences or walls.
                // Iron Golems are 1.4m wide; walking on a 1x1 block directly adjacent to a fence causes
                // their hitbox to overhang 0.2m into the fence and snag against posts.
                if (neighbor.costMalus >= 0.0F && golemsActuallyProtectVillagers$isAdjacentToFenceOrWall(nx, ny, nz)) {
                    float fenceMalus = (Config.DATA != null && Config.DATA.fenceProximityMalus >= 0.0F)
                            ? Config.DATA.fenceProximityMalus
                            : 2.5F;
                    neighbor.costMalus += fenceMalus;
                }

                // 4. Climb malus: penalize nodes that require climbing up ledges, fences, or walls
                // so the golem strictly prefers flat ground and open gates when available
                if (neighbor.costMalus >= 0.0F && holder.golemsActuallyProtectVillagers$canClimbLedge()) {
                    int dy = ny - node.y;
                    boolean isLedgeClimb = (dy >= 2);
                    boolean isFence = false;
                    if (this.currentContext != null && dy >= 0) {
                        BlockState stateBelow = this.currentContext.getBlockState(
                                this.golemsActuallyProtectVillagers$scratchNeighborPos.set(nx, ny - 1, nz));
                        isFence = stateBelow.is(BlockTags.FENCES) || stateBelow.is(BlockTags.WALLS);
                    }
                    if (isLedgeClimb || isFence) {
                        float malus = (Config.DATA != null && Config.DATA.climbMalus >= 0.0F)
                                ? Config.DATA.climbMalus
                                : 5.0F;
                        neighbor.costMalus += malus;
                    }
                }

                output[validCount++] = neighbor;
            }
            cir.setReturnValue(validCount);
        }
    }

    @Unique
    private boolean golemsActuallyProtectVillagers$isAdjacentToFenceOrWall(int x, int y, int z) {
        if (this.currentContext == null) {
            return false;
        }
        BlockPos.MutableBlockPos pos = this.golemsActuallyProtectVillagers$scratchNeighborPos;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int dx = dir.getStepX();
            int dz = dir.getStepZ();
            if (golemsActuallyProtectVillagers$isFenceOrWall(pos.set(x + dx, y, z + dz))) {
                return true;
            }
            if (golemsActuallyProtectVillagers$isFenceOrWall(pos.set(x + dx, y - 1, z + dz))) {
                return true;
            }
            if (golemsActuallyProtectVillagers$isFenceOrWall(pos.set(x + dx, y + 1, z + dz))) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean golemsActuallyProtectVillagers$isFenceOrWall(BlockPos pos) {
        BlockState state = this.currentContext.getBlockState(pos);
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) {
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return !state.getValue(FenceGateBlock.OPEN);
        }
        return false;
    }

    @Redirect(method = "getNeighbors", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;maxUpStep()F"))
    private float golemsActuallyProtectVillagers$redirectNeighborsMaxUpStep(Mob mob) {
        if (mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$canClimbLedge()) {
            return (Config.DATA != null && Config.DATA.maxClimbHeight > 0)
                    ? (float) Config.DATA.maxClimbHeight
                    : 2.0F;
        }
        return mob.maxUpStep();
    }

    @Redirect(method = "getMobJumpHeight", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;maxUpStep()F"))
    private float golemsActuallyProtectVillagers$redirectJumpHeightMaxUpStep(Mob mob) {
        if (mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$canClimbLedge()) {
            return (Config.DATA != null && Config.DATA.maxClimbHeight > 0)
                    ? (float) Config.DATA.maxClimbHeight + 0.25F
                    : 2.25F;
        }
        return mob.maxUpStep();
    }

    @Inject(method = "findAcceptedNode", at = @At("HEAD"))
    private void golemsActuallyProtectVillagers$onFindAcceptedNodeHead(
            int x, int y, int z, int jumpSize, double nodeHeight, Direction travelDirection,
            PathType blockPathTypeCurrent, CallbackInfoReturnable<Node> cir) {
        this.golemsActuallyProtectVillagers$currentEvaluatingPos.set(x, y, z);
    }

    @Redirect(method = "findAcceptedNode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/WalkNodeEvaluator;canWalkOverFences()Z"))
    private boolean golemsActuallyProtectVillagers$redirectCanWalkOverFences(WalkNodeEvaluator evaluator) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$canClimbLedge()) {
            if (this.currentContext != null) {
                BlockState stateAt = this.currentContext.getBlockState(this.golemsActuallyProtectVillagers$currentEvaluatingPos);
                // ONLY allow jumping onto/over fences if the physical block at (x, y, z) is an actual fence or wall!
                // If it's a ground node next to a fence, its 2x2 bounding box merely grazes the adjacent fence.
                // It must return false (like vanilla) so the wide 1.4m golem is forced to path 1 block away from the fence!
                return stateAt.is(BlockTags.FENCES) || stateAt.is(BlockTags.WALLS);
            }
            return true;
        }
        return evaluator.canWalkOverFences();
    }
}
