<div align="center">

[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1788125?logo=curseforge&label=CurseForge&color=F16436&style=for-the-badge)](https://www.curseforge.com/minecraft/mc-mods/golems-actually-protect-villagers)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/FbJqfA7u?logo=modrinth&label=Modrinth&color=00AF5C&style=for-the-badge)](https://modrinth.com/mod/golems-actually-protect-villagers)

[![Fabric](https://img.shields.io/badge/Fabric-DBD0B4?style=for-the-badge&logo=fabric&logoColor=1f1f1f)](https://fabricmc.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-E05D26?style=for-the-badge&logo=forge&logoColor=white)](https://neoforged.net/)

[![1.21.1](https://img.shields.io/badge/1.21.1-3C8527?style=for-the-badge&logo=minecraft&logoColor=white)](https://modrinth.com/mod/golems-actually-protect-villagers/versions)
[![26.x](https://img.shields.io/badge/26.x-3C8527?style=for-the-badge&logo=minecraft&logoColor=white)](https://modrinth.com/mod/golems-actually-protect-villagers/versions)
[![26.3](https://img.shields.io/badge/26.3-3C8527?style=for-the-badge&logo=minecraft&logoColor=white)](https://modrinth.com/mod/golems-actually-protect-villagers/versions)

</div>

In vanilla Minecraft, Iron Golems are sooooo bad at protecting villagers. This mod overhauls how golems choose their targets and how far they reach for targets, making them actively defend villagers from danger instead of getting distracted by random mobs, or not acting at all.

## Features

- Golems receive distress signals from villagers, then proceed to attack what's hurting them.
- Range of mob detection (due to sos by villager) is higher than vanilla.
- Mobs that don't attack villagers (like spiders or skeletons) aren't targeted by golems.
- Mobs that are not a threat, such as zombies stuck in pits or caves, are not targeted by golems.
- Golems will not wander inside places they can't get out of, unless a villager in danger is inside too.
- Paths are calculated in the background on a separate thread, so large search ranges don't lag the main thread.
- If the village is safe, golems fall back to normal vanilla behavior (wandering, handing out roses, and patrolling).

## How It Works

1. Golems continuously (async, off-thread and fast) scan their surroundings to check for places they shouldn't go in, such as 2 blocks deep pits, caves or deep water.
2. When a villager is attacked or panics, they broadcast an instant SOS signal to all golems within a 48-block (configurable) radius.
3. Enemies are scored based on real danger to the village:
   - Actively targeting a villager (+100)
   - Damaging a villager recently (+80)
   - Zombie breaking a villager's door (+40)
   - Villager is panicking (+25)
   - Proximity to villagers (closer = higher urgency)
4. The score is scaled by the walking path distance from the golem to the threat. A threat 5 blocks away from the golem will be prioritized over one 40 blocks away.
5. Golems evaluate safe paths off-thread to avoid lag. They can drop down cliffs up to 32 blocks (configurable) and leap into dangerous places to save villagers.
6. Golems gain a +20% sprint speed boost (configurable) during active rescues, keep pursuing up to 10 blocks past the scan radius.
7. If a monster is trapped in a pit or dangerous place, and villagers are not at risk, the golems will hit the mobs from the edge of the pit, or outright disengage.
8. Golem disengages when the target is lost or dead.

## Configuration

| Option | Default | Description |
|---|---|---|
| `golemScanRadius` | `48.0` | Maximum radius (in blocks) a golem scans for threats and villagers |
| `idleScanIntervalTicks` | `100` | Interval in ticks between threat scans when a golem is idle (100 ticks = 5 seconds) |
| `villagerProximityThreatRadius` | `10.0` | Radius around a villager where hostile mobs count as an immediate danger |
| `singleGolemQueueLimit` | `5` | Maximum threat candidates evaluated per golem in a single async cycle |
| `maxPathfindingNodes` | `1000` | Maximum pathfinding node depth during off-thread A* evaluation |
| `asyncQueueCapacity` | `1000` | Size of the background pathfinding thread pool queue |
| `enableChatDebug` | `false` | Shows golem target decisions in chat |
| `enableDebugLogs` | `false` | Prints extra logs to the server/game console |
| `maxFallDistance` | `32` | Maximum vertical fall distance allowed when pathfinding to threats |
| `mobPursuitSpeedModifier` | `0.20` | Movement speed modifier applied to iron golems when pursuing hostile mobs |
| `enableVillagerDistressSignals` | `true` | Enables event-driven villager distress beacon system |
| `villagerDistressBroadcastIntervalTicks` | `40` | Interval in ticks between distress broadcasts per villager while panicking |
| `enablePeriodicGolemScan` | `false` | Enables fallback periodic idle scanning by iron golems |

