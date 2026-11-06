# Golems Actually Protect Villagers

A high-performance, intelligent Minecraft mod that transforms Iron Golems into true guardians of the village. Using a stateless, on-demand threat evaluation goal, golems actively prioritize defending villagers from imminent harm rather than blindly attacking whatever monster is physically closest to them.

Available for **Fabric** and **NeoForge** (Minecraft 26.3+).

---

## The Problem with Vanilla Golems

In vanilla Minecraft:
1. **Deceptive `DefendVillageTargetGoal`:** Despite its name, vanilla's goal only attacks *Players* with low reputation ($\le -100$). It contains zero logic for defending against monsters.
2. **Door & Wall Blindness:** Vanilla golems require direct line-of-sight (`hasLineOfSight`). If a zombie is banging on a wooden house door trying to kill a villager, the door blocks line-of-sight, and the golem casually strolls past.
3. **Proximity Bias:** Golems only target the mob closest to *the golem*. A golem will chase a distant spider in an open field while a zombie slaughters a villager 10 blocks away.
4. **Zero Panic Awareness:** Golems ignore villager damage, screaming/fleeing panic states, and door breaches.

---

## Features

### 1. Stateless On-Demand Threat Evaluation
Unlike complex queue architectures that store historical alerts and risk chasing stale ghosts, this mod uses a **purely stateless target goal** (`ProtectVillagersTargetGoal` at Priority 2):
- Whenever a golem is idle, it scans nearby enemies and living villagers in real-time.
- Only enemies currently posing a genuine threat to villagers are considered.
- Zero memory footprint, zero background thread overhead, zero stale targets.

### 2. Async Pathfinding via Bounded Worker Queue
- Evaluates true A* walking distance (`Path.getNodeCount()`) and reachability (`path.canReach()`) off the main thread in a dedicated background worker thread pool (`Constants.ASYNC_POOL`).
- **Bounded Worker Queue:** Uses a bounded `LinkedBlockingQueue` (default capacity `1000`) with `DiscardOldestPolicy`, preventing server CPU hangs or memory leaks during large village sieges.
- **Snapshot Navigation Region:** Captures a snapshot `PathNavigationRegion` on the server thread so background threads never access mutable level state unsafely.
- **Instant Movement Hand-off:** Pre-computed paths are returned to the server thread and passed directly to the golem's navigation for 0-tick responsiveness.

### 3. Balanced Danger Scoring Scaled by Pathfinded Distance
Candidate hostiles around villagers are scored dynamically based on their danger and scaled by the golem's actual **pathfinded distance**:

1. **Base Urgency Factors:**
   - **Actively Targeting a Villager (+100.0 pts):** Mobs pursuing or targeting a living villager.
   - **Recent Villager Attacker (+80.0 pts):** Mobs that damaged a villager within the last 10 seconds (`villager.getLastHurtByMob()`).
   - **Proximity Threat (up to +50.0 pts):** Hostiles within 10 blocks (`villagerProximityThreatRadius`) of a villager scaled by closeness.
   - **Door Breaker Response (+40.0 pts):** Zombies actively pounding on wooden doors near villagers.
   - **Panic State (+25.0 pts):** Hostiles causing villagers to flee in panic.

2. **Distance-Scaled Balance:**
   $$\text{Final Score} = \frac{\text{Base Urgency}}{1.0 + (\text{pathDistance} / 10.0)}$$
   - Golems evaluate real walking nodes, accounting for walls, fences, and obstacles.
   - Every 10 blocks of walking distance reduces score influence (10 blocks = 50%, 20 blocks = 33%, 30 blocks = 25%).
   - **Balanced Decision-Making:** A golem won't sprint 35 blocks away just because a distant villager is being chased if a zombie is already 4 blocks away menacing another villager. However, acute emergencies (e.g. a villager being actively mauled) have enough urgency to pull the golem across moderate distances.

### 4. Natural Vanilla Target Persistence
- Once an Iron Golem engages an objective, it **stays committed** to that fight until the target is defeated, lost, or out of range (maintaining vanilla combat persistence).
- Once its target dies, the golem immediately re-evaluates the environment and engages the next highest-scoring threat threatening the village.

---

## Configuration

The mod can be configured via `config/golems_actually_protect_villagers.json`:

```json
{
  "villagerProximityThreatRadius": 10.0,
  "golemScanRadius": 32.0,
  "singleGolemQueueLimit": 5,
  "maxPathfindingNodes": 200,
  "asyncQueueCapacity": 1000,
  "enableQueueDebug": false,
  "enableChatDebug": true,
  "enableDebugLogs": false
}
```

| Setting | Default | Description |
| :--- | :--- | :--- |
| `golemScanRadius` | `32.0` | Radius (blocks) around the golem to scan for threats and villagers. |
| `villagerProximityThreatRadius` | `10.0` | Distance from a villager to consider an enemy an acute danger. |
| `singleGolemQueueLimit` | `5` | Maximum candidates evaluated per golem in a single async evaluation. |
| `maxPathfindingNodes` | `200` | Maximum A* node limit for background pathfinding exploration. |
| `asyncQueueCapacity` | `1000` | Capacity of the background thread pool queue. |
| `enableQueueDebug` | `false` | Displays real-time async pathfinding throughput in chat. |
| `enableChatDebug` | `true` | Broadcasts real-time golem AI actions and decisions to in-game chat. |
| `enableDebugLogs` | `false` | Enables detailed logging of target engagement in console. |
