# Chunk Block Limiter

A Fabric mod that limits the number of specific blocks players can place per chunk. Perfect for server performance optimization and preventing lag machines.

## Features

- **Chunk-Accurate Limits** - Enforcement is based on scanning the real blocks in the chunk, so it cannot be spoofed
- **Best-Effort Player Visibility** - Admins can see per-player tracked placements alongside the authoritative chunk totals
- **Block Breaking Restores Slots** - When you break a limited block, you get that "slot" back
- **Admin Bypass** - OPs can bypass all limits
- **Fully Configurable** - Set limits for any block via JSON config
- **Warning System** - Warns players when approaching limits
- **MiniMessage Support** - Rich text formatting for messages
- **Commands** - Check chunk contents, view config, admin tools

## Warning

This mod tracks blocks based on how much space they take up in a chunk. A two-block-tall block (like a bed) counts as 2 towards the limit. Adjust your limits accordingly.

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/chunklimit` or `/cl` | Show help | Everyone |
| `/chunklimit scan` | Scan current chunk for limited blocks (authoritative) | Everyone |
| `/chunklimit limits` | Show all configured block limits | Everyone |
| `/chunklimit summary <player>` | Show tracked totals for a player (no chunk scan) | OP |
| `/chunklimit reload` | Reload configuration | OP |
| `/chunklimit clear <player>` | Clear a player's data in current chunk | USELESS RIGHT NOW|
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
  "limitReachedMessage": "<red>You can only place <yellow><limit></yellow> <gold><block></gold> per chunk! (Currently: <current>)</red>",
  "limitWarningMessage": "<yellow>Warning: You have placed <gold><current>/<limit></gold> <block> in this chunk.</yellow>",
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

- `<block>` - Block name (e.g., "hopper")
- `<limit>` - Maximum allowed per chunk
- `<current>` - Current count placed

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

1. On the first placement of a limited block in a chunk (and only if counters are zero), the mod runs one real chunk scan to seed counts (picks up pre-mod builds).
2. After seeding, placements/breaks are O(1) counter updates via mixins; no more scans for that chunk+block combo.
3. If over the limit, placement is blocked and a message is shown; warnings use the tracked counts.
4. Data persists across server restarts.

### Admin Player Checks

- `/chunklimit summary <player>`: tracked totals only (no chunk scanning, instant).
- `/chunklimit scan`: live authoritative counts for the chunk you are standing in.

### Performance Note

- Each (chunk, block type) is scanned at most once to seed counters; after that enforcement is pure counter math.
- Chunk scans are still cached per block type per chunk for ~30 seconds (useful for `/chunklimit scan`) and invalidated only when that block type changes.


## Data Storage

- **Chunk data**: `config/chunk_block_limiter/data/<dimension>_<x>_<z>.json`
- **Placement tracker**: `config/chunk_block_limiter/placements.json`

## Installation

1. Install [Fabric API](https://modrinth.com/mod/fabric-api)
2. Place the mod JAR in your `mods/` folder
3. Start the server - config will be auto-generated
4. Customize `config/chunk_block_limiter/config.json` as needed


## License

MIT
