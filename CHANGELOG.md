# Changelog

## 1.0.0
- **Stateless On-Demand Target Goal (`ProtectVillagersTargetGoal`)**: Purely stateless evaluation registered at Priority 2. Re-evaluates threats live in real-time when the golem is idle.
- **Dynamic Threat Scoring**: Ranks threats based on active villager targeting, recent damage, close proximity, door-breaking, and panic.
- **Pathfinded Distance Scaling**: Scales base urgency inversely by real navigation walking steps (`Path.getNodeCount()`), ensuring close threats are balanced fairly against distant emergencies.
- **Async Bounded Pathfinder Queue (`ASYNC_POOL`)**: Off-thread A* path calculation using a bounded daemon thread pool queue (`LinkedBlockingQueue` with `DiscardOldestPolicy`) and snapshot `PathNavigationRegion`, keeping the main server thread lag-free.
- **Full MultiLoader Support**: Common code shared seamlessly across Fabric and NeoForge (Minecraft 26.3+).
