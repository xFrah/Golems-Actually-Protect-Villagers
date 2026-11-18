package com.fdimo.golemsactuallyprotectvillagers.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;

public class ThreatCandidate {

    private final Mob mob;
    private final Villager imperiledVillager;
    private Path path;
    private boolean reachable = false;
    private int pathDistance = Integer.MAX_VALUE;

    public ThreatCandidate(Mob mob, Villager imperiledVillager) {
        this.mob = mob;
        this.imperiledVillager = imperiledVillager;
    }

    public Mob getMob() {
        return this.mob;
    }

    public Villager getImperiledVillager() {
        return this.imperiledVillager;
    }

    public Path getPath() {
        return this.path;
    }

    public boolean isReachable() {
        return this.reachable;
    }

    public int getPathDistance() {
        return this.pathDistance;
    }

    public void setPathResult(Path path, boolean reachable, int pathDistance) {
        this.path = path;
        this.reachable = reachable;
        this.pathDistance = pathDistance;
    }
}
