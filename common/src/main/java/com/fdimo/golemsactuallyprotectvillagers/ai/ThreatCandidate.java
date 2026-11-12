package com.fdimo.golemsactuallyprotectvillagers.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Path;

public class ThreatCandidate {

    private final Mob mob;
    private final double baseUrgency;
    private final double euclideanDist;
    private Villager imperiledVillager;
    private Path path;
    private boolean reachable = false;
    private int pathDistance = Integer.MAX_VALUE;
    private double finalScore = 0.0;

    public ThreatCandidate(Mob mob, double baseUrgency, double euclideanDist) {
        this.mob = mob;
        this.baseUrgency = baseUrgency;
        this.euclideanDist = euclideanDist;
    }

    public Mob getMob() {
        return this.mob;
    }

    public double getBaseUrgency() {
        return this.baseUrgency;
    }

    public double getEuclideanDist() {
        return this.euclideanDist;
    }

    public Path getPath() {
        return this.path;
    }

    public Villager getImperiledVillager() {
        return this.imperiledVillager;
    }

    public void setImperiledVillager(Villager imperiledVillager) {
        this.imperiledVillager = imperiledVillager;
    }

    public boolean isReachable() {
        return this.reachable;
    }

    public int getPathDistance() {
        return this.pathDistance;
    }

    public double getFinalScore() {
        return this.finalScore;
    }

    public void setPathResult(Path path, boolean reachable, int pathDistance) {
        this.path = path;
        this.reachable = reachable;
        this.pathDistance = pathDistance;

        // Balanced distance scaling: points are scaled inversely by pathfinded distance
        // Every 10 blocks of path distance adds 1.0 to the denominator (10 blocks = 50%, 20 blocks = 33%, 30 blocks = 25%)
        double distanceFactor = 1.0 + (pathDistance / 10.0);
        this.finalScore = this.baseUrgency / distanceFactor;
    }
}
