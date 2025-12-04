Short version: this is really solid. The seeding logic is correct, the mixins are wired properly, and the “one-time scan + counters” behaviour is basically what you want. There are just a couple of subtle logic gotchas and a few polish points I’d fix.

I’ll go through the important bits, then list concrete changes.

---

## 1. `ChunkBlockLimiter.shouldBlockPlacement` + seeding

This is the heart of it:

```java
ChunkPos chunkPos = new ChunkPos(pos);
String seedKey = seedKey(level, chunkPos, blockId);
int trackedCount = storage.getTotalCount(level.dimension(), pos, blockId);

// One-time seed from world if this chunk/block hasn't been initialised and is currently empty in counters
if (trackedCount == 0 && !seededCounts.contains(seedKey)) {
    int actualCount = countBlocksInChunk(level, pos, blockId);
    if (actualCount > 0) {
        storage.seedBlockCount(level.dimension(), chunkPos, blockId, actualCount);
        trackedCount = actualCount;
    }
    seededCounts.add(seedKey);
}

int nextCount = trackedCount + 1;

if (nextCount > limit) {
    sendLimitReached(player, blockId, limit, trackedCount);
    return true;
}
```

✅ Behaviour-wise this is correct:

* First time you ever place block `B` in chunk `C`:

  * If counters say “0 in this chunk” and you haven’t seeded this `(C,B)` yet → do a **single real scan**.
  * Seed storage to the real count (including pre-mod blocks).
  * From then on, use counters only.
* If pre-mod count ≥ limit, you block immediately on the first new placement after seeding.
* If pre-mod count < limit, you allow up to `limit` total, including the old blocks.

That matches what you described you’re okay with.

Also nice:

* You have a clean `storage == null` fallback to a one-off scan.
* `seededCounts` is in-memory only, but because you persist chunk counters, you **don’t** re-seed on restart (since `trackedCount` will be > 0). Good.

So the main “counter + one-time scan per chunk per block type” logic is correct.

---

## 2. Important subtlety: seeded counts never go *down*

This is the one real logic issue I’d flag.

You seed totals via:

```java
public void seedBlockCount(ResourceKey<Level> dimension, ChunkPos chunkPos, String blockId, int totalCount) {
    ChunkBlockData data = getOrCreate(dimension, chunkPos);
    int existing = data.getTotalCount(blockId);
    int toAdd = totalCount - existing;
    if (toAdd <= 0) return;

    data.addToPlayer(SYSTEM_UUID, blockId, toAdd);
    markDirty(getChunkKey(dimension, chunkPos));
}
```

But when blocks are broken, you only decrement **if** you can match a placement entry:

```java
public static void onBlockBroken(ServerPlayer breaker, BlockPos pos, BlockState blockState) {
    ...
    java.util.UUID placerId = tracker.removePlacement(breaker.level().dimension(), pos);
    
    if (placerId != null) {
        storage.decrementBlock(breaker.level().dimension(), pos, placerId, blockId);
        ...
    }
}
```

There’s no fallback for seeded/system counts. So:

* Pre-mod world has 40 PCs.
* First new placement in that chunk:

  * Seed to 40, then counters track 41, 42, … etc.
* Player breaks one of the original 40 PCs:

  * `removePlacement` returns `null` (no record → pre-mod or /setblock).
  * Counters in `ChunkBlockStorage` **do not decrement**.
  * Your stored total stays higher than the real world.

This means:

* Over time, if people break a lot of those old seeded blocks, your system can think the chunk is “full” even when the world actually has fewer blocks.
* In the extreme, a chunk that was once above the limit can remain logically “over limit” forever, even if players remove everything.

Given what you said (“I don’t care about existing ones, worst case we stop them placing more”) that might be acceptable, but it’s *stricter* than you probably intend: breaking old blocks **does not** free up limit.

### Easy fix

Let the system bucket be decremented when we don’t know the placer:

In `ChunkBlockStorage`, expose a helper for “system” decrement, e.g.:

```java
// ChunkBlockStorage.java

public static final UUID SYSTEM_UUID = new UUID(0L, 0L); // make this public

public int decrementSystemBlock(ResourceKey<Level> dimension, BlockPos pos, String blockId) {
    String key = getChunkKey(dimension, pos);
    ChunkBlockData data = getOrCreate(dimension, pos);
    int newCount = data.decrement(SYSTEM_UUID, blockId);
    markDirty(key);
    return newCount;
}
```

Then in `ChunkBlockLimiter.onBlockBroken`:

```java
public static void onBlockBroken(ServerPlayer breaker, BlockPos pos, BlockState blockState) {
    ...
    UUID placerId = tracker.removePlacement(breaker.level().dimension(), pos);
    
    if (placerId != null) {
        storage.decrementBlock(breaker.level().dimension(), pos, placerId, blockId);
    } else {
        // Fallback: treat it as a system/seeded block
        storage.decrementSystemBlock(breaker.level().dimension(), pos, blockId);
    }

    if (config.get().debug) {
        Constants.LOGGER.info("[DEBUG] Block broken: {} at {} by {} (placer: {})",
            blockId, pos, breaker.getScoreboardName(), placerId);
    }
}
```

Now:

* Seeded counts will *reduce* as those old blocks are broken.
* Limit enforcement stays close to real world state.
* You still don’t attribute those blocks to any specific player.

---

## 3. Performance gotcha in debug logging

In `onBlockPlaced`:

```java
if (config.get().debug) {
    int chunkCount = 0;
    if (player.level() instanceof ServerLevel serverLevel) {
        chunkCount = countBlocksInChunk(serverLevel, pos, blockId);
    }
    Constants.LOGGER.info("[DEBUG] Block placed: {} at {} by {} (chunk total: {})", 
        blockId, pos, player.getScoreboardName(), chunkCount);
}
```

This calls `countBlocksInChunk` on **every placement** when debug is enabled, which:

* Completely defeats the performance optimisations if someone turns `debug=true` in production.
* Is basically “full-chunk scan per placement” again.

Safer option: log from storage:

```java
if (config.get().debug) {
    int chunkCount = storage.getTotalCount(player.level().dimension(), pos, blockId);
    Constants.LOGGER.info("[DEBUG] Block placed: {} at {} by {} (chunk total: {})",
        blockId, pos, player.getScoreboardName(), chunkCount);
}
```

That way debug mode is cheap too.

---

## 4. Smaller correctness / consistency notes

Nothing here is *broken*, just stuff to be aware of:

* **`PlacementContext` is unused.**
  Totally fine, but you can safely delete it unless you plan to use it later.

* **`getAllPlayerData` filename–>chunkKey logic is a bit janky**, but functionally OK for your current version.
  You do a `replace("_", ":")` on filenames, which would turn `minecraft_overworld_0_0` into `minecraft:overworld:0:0`. But you then load the JSON, and rely on `data.chunkKey` (which was set correctly when saving) when putting into `result`. So:

  * For files created by this version, you’re fine.
  * If you ever had older files with `chunkKey == null`, that fallback `chunkKey` would be wrong — but probably not an issue for you.

* **Mod ID vs config directory name:**

  ```java
  public static final String MOD_ID = "chunk-block-limiter";
  ```

  while `fabric.mod.json` has:

  ```json
  "id": "chunk_block_limiter"
  ```

  That’s allowed (Fabric only cares about the JSON `id`), but it means your config and data live under `config/chunk-block-limiter/`, not `config/chunk_block_limiter/`. As long as you’re aware, that’s fine; if you want “nice” consistency you might rename the constant.

* **Comments slightly outdated:**
  `shouldBlockPlacement` / `BlockPlaceMixin` comments still say “uses ACTUAL block counting” as if it scans on every call. Behaviour is now “counters + one-time scan”. Not a bug, just worth updating so Future You isn’t confused.

---

## 5. Mixins / command wiring / data model

All of this looks correct:

* `BlockPlaceMixin`:

  * HEAD injection on `useItemOn` is correct, parameters line up for 1.21.
  * You correctly:

    * detect target block ID,
    * compute `placePos` (replaceable vs adjacent),
    * run `shouldBlockPlacement`,
    * on success, set pending block ID,
    * on RETURN, only call `onBlockPlaced` if `result.consumesAction()` and the block actually appears at hit or adjacent pos.
* `BlockBreakMixin`:

  * HEAD captures `BlockState` before break.
  * RETURN only processes if the block was actually destroyed (`Boolean result == true`).
  * Uses `BreakContext` thread-local properly.
* `ChunkLimitCommand`:

  * `/scan` uses `countBlocksInChunk` (which is what you want for an “actual world state” admin view).
  * `/summary` correctly uses `getAllPlayerData`.
  * `clear` correctly manipulates `ChunkBlockData` and marks dirty.
* Persistence:

  * `ChunkBlockStorage` and `BlockPlacementTracker` use Fabric’s config dir and JSON; the lifecycle hooks in `ChunkBlockLimiter` call `saveDirty` / `saveAll` and `tracker.saveIfDirty()` at sensible times.

---

## TL;DR

**Yes**, your current code is broadly correct and the “one scan per chunk+block” seeding logic works as intended.

If you:

1. Add a fallback system decrement in `onBlockBroken` (so seeded totals can go down), and
2. Change the debug logging to use `storage.getTotalCount` instead of `countBlocksInChunk`,

you’ll have a mod that’s:

* logically consistent,
* performant enough,
* and behaves very intuitively for players (“break blocks to free up space again”).

If you’d like, I can write the exact patched versions of `ChunkBlockLimiter.onBlockBroken` and `ChunkBlockStorage` with those tweaks already merged so you can just paste them in.
