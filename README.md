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

- Golems prioritize enemies that are actively hunting, hurting, or cornering villagers.
- Mobs that don't attack villagers (like spiders or skeletons) aren't targeted by golems.
- The mod's new mechanism doesn't target drowneds in the water.
- Paths are calculated in the background on a separate thread, so large search ranges don't lag the main threads.
- If the village is safe, golems fall back to normal vanilla behavior (wandering, handing out roses, and patrolling).

## How It Works

1. When near living villagers, the golem scans a 48-block radius for threats.
2. Enemies are scored based on real danger to the village:
   - Actively targeting a villager (+100)
   - Damaging a villager recently (+80)
   - Zombie breaking a villager's door (+40)
   - Villager is panicking (+25)
   - Proximity to villagers (closer = higher urgency)
3. The score is scaled by the actual walking path distance from the golem to the threat. A threat 5 blocks away from the golem will be prioritized over one 40 blocks away.
4. Golems keep pursuing their target up to 10 blocks past the scan radius, and take a brief 1-second breather after defeating a target before re-scanning.

## Configuration

| Option | Default | Description |
|---|---|---|
| `golemScanRadius` | `48.0` | Maximum radius (in blocks) a golem scans for threats and villagers |
| `idleScanIntervalTicks` | `100` | Interval in ticks between threat scans when a golem is idle (100 ticks = 5 seconds) |
| `villagerProximityThreatRadius` | `10.0` | Radius around a villager where hostile mobs count as an immediate danger |
| `singleGolemQueueLimit` | `5` | Maximum threat candidates evaluated per golem in a single async cycle |
| `maxPathfindingNodes` | `200` | Maximum pathfinding node depth during off-thread A* evaluation |
| `asyncQueueCapacity` | `1000` | Size of the background pathfinding thread pool queue |
| `enableChatDebug` | `false` | Shows golem target decisions in chat |
| `enableDebugLogs` | `false` | Prints extra logs to the server/game console |

