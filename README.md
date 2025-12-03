# Chunk Block Limiter

A Fabric mod that limits the number of specific blocks players can place per chunk. Perfect for server performance optimization and preventing lag machines.

## Features

- **Per-Player, Per-Chunk Tracking** - Each player has their own limit in each chunk
- **Block Breaking Restores Slots** - When you break a limited block, you get that "slot" back
- **Admin Bypass** - OPs can bypass all limits
- **Fully Configurable** - Set limits for any block via JSON config
- **Warning System** - Warns players when approaching limits
- **MiniMessage Support** - Rich text formatting for messages
- **Commands** - Check your limits, view config, admin tools

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/chunklimit` or `/cl` | Show help | Everyone |
| `/chunklimit check` | Check your block counts in current chunk | Everyone |
| `/chunklimit limits` | Show all configured block limits | Everyone |
| `/chunklimit checkplayer <player>` | Check another player's counts | OP |
| `/chunklimit reload` | Reload configuration | OP |
| `/chunklimit clear <player>` | Clear a player's data in current chunk | OP |
| `/chunklimit debug` | Show debug information | OP |
| `/chunklimit save` | Force save all data | OP |

## Configuration

Config file: `config/chunk_block_limiter/config.json`

```json
{
  "enabled": true,
  "blockLimits": {
    "minecraft:hopper": 16,
    "minecraft:chest": 54,
    "minecraft:trapped_chest": 27,
    "minecraft:barrel": 54,
    "minecraft:dispenser": 16,
    "minecraft:dropper": 16,
    "minecraft:piston": 32,
    "minecraft:sticky_piston": 32,
    "minecraft:observer": 32,
    "minecraft:comparator": 32,
    "minecraft:repeater": 64,
    "minecraft:redstone_lamp": 32,
    "cobblemon:healing_machine": 2,
    "cobblemon:pc": 4
  },
  "bypassPermissionLevel": 2,
  "sendLimitMessage": true,
  "limitReachedMessage": "<red>You can only place <yellow>%limit%</yellow> <gold>%block%</gold> per chunk! (Currently: %current%)</red>",
  "limitWarningMessage": "<yellow>Warning: You have placed <gold>%current%/%limit%</gold> %block% in this chunk.</yellow>",
  "warningThreshold": 0.8,
  "showWarnings": true
}
```

### Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `enabled` | Enable/disable the entire mod | `true` |
| `blockLimits` | Map of block ID to max count per chunk per player | (see above) |
| `bypassPermissionLevel` | Permission level to bypass limits (2 = OP) | `2` |
| `sendLimitMessage` | Send message when limit is reached | `true` |
| `limitReachedMessage` | Message when placement is blocked | (see above) |
| `limitWarningMessage` | Warning message when approaching limit | (see above) |
| `warningThreshold` | Percentage (0.0-1.0) to show warning | `0.8` |
| `showWarnings` | Show warning messages | `true` |

### Message Placeholders

- `%block%` - Block name (e.g., "hopper")
- `%limit%` - Maximum allowed per chunk
- `%current%` - Current count placed

### Adding Custom Blocks

Add any block ID to the `blockLimits` map:

```json
"blockLimits": {
  "minecraft:tnt": 4,
  "minecraft:beacon": 1,
  "create:mechanical_press": 8,
  "cobblemon:fossil_analyzer": 2
}
```

Use `/chunklimit reload` to apply changes without restarting.

## How It Works

1. When a player places a limited block, the mod checks their count for that chunk
2. If under the limit, placement is allowed and count is incremented
3. If at the limit, placement is blocked and a message is shown
4. When ANY player breaks a limited block, the original placer's count is decremented
5. Data persists across server restarts

## Data Storage

- **Chunk data**: `config/chunk_block_limiter/data/<dimension>_<x>_<z>.json`
- **Placement tracker**: `config/chunk_block_limiter/placements.json`

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21+
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Place the mod JAR in your `mods/` folder
4. Start the server - config will be auto-generated
5. Customize `config/chunk_block_limiter/config.json` as needed

## Building

```bash
./gradlew build
```

Output will be in `build/libs/`.

## License

MIT