package com.moulberry.flashbackexport;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WorldExporter {

    private static final int DATA_VERSION = 3953;
    private static final String VERSION_NAME = "1.21.10";
    private static final byte IS_SNAPSHOT = (byte) 0;

    public static volatile boolean isExporting = false;
    public static volatile String statusMessage = "";
    public static volatile int chunksWritten = 0;
    public static volatile int chunksTotal = 0;

    public static void startExport() {
        if (isExporting) return;

        var replayServer = Flashback.getReplayServer();
        if (replayServer == null) {
            FlashbackExportMod.LOGGER.error("Cannot export: not in a replay");
            return;
        }

        isExporting = true;
        chunksWritten = 0;
        chunksTotal = 0;
        statusMessage = "Starting export...";
        sendActionBar("§e> Starting world export...");

        Thread exportThread = new Thread(() -> {
            try {
                doExport(replayServer);
            } catch (Exception e) {
                FlashbackExportMod.LOGGER.error("World export failed", e);
                statusMessage = "Export failed: " + e.getMessage();
                sendActionBar("§c> Export failed: " + e.getMessage());
            } finally {
                isExporting = false;
            }
        }, "flashback-world-exporter");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    private static void doExport(net.minecraft.client.server.IntegratedServer server) throws IOException {
        Minecraft mc = Minecraft.getInstance();

        // Use the actual replay name from FlashbackMeta
        var replayServer = Flashback.getReplayServer();
        String replayName = replayServer != null ? replayServer.getMetadata().name : "Flashback";
        // Sanitize for use as folder name
        String worldName = replayName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (worldName.isEmpty()) worldName = "Flashback";

        Path savesDir = mc.gameDirectory.toPath().resolve("saves");

        // Avoid overwriting existing worlds
        Path worldPath = savesDir.resolve(worldName);
        if (Files.exists(worldPath)) {
            int i = 1;
            while (Files.exists(savesDir.resolve(worldName + " " + i))) i++;
            worldName = worldName + " " + i;
            worldPath = savesDir.resolve(worldName);
        }
        Files.createDirectories(worldPath);

        statusMessage = "Creating world structure...";
        sendActionBar("§e> Creating world structure...");

        // Use player position as spawn
        double spawnX = 0, spawnY = 64, spawnZ = 0;
        if (mc.player != null) {
            spawnX = mc.player.getX();
            spawnY = mc.player.getY();
            spawnZ = mc.player.getZ();
        }

        createLevelDat(worldPath, worldName, spawnX, spawnY, spawnZ, mc);

        // Collect all loaded chunks
        List<LevelChunkEntry> allChunks = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            try {
                var chunkMap = level.getChunkSource().chunkMap;
                for (java.lang.reflect.Field f : chunkMap.getClass().getDeclaredFields()) {
                    if (f.getType().getSimpleName().contains("Long2Object")) {
                        f.setAccessible(true);
                        Object map = f.get(chunkMap);
                        if (map instanceof java.util.Map<?, ?> m) {
                            for (Object obj : m.values()) {
                                if (obj instanceof net.minecraft.server.level.ChunkHolder ch) {
                                    LevelChunk chunk = ch.getTickingChunk();
                                    if (chunk != null) {
                                        allChunks.add(new LevelChunkEntry(chunk, dimension, level));
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                FlashbackExportMod.LOGGER.warn("Failed to iterate chunks for {}", dimension.location(), e);
            }
        }

        chunksTotal = allChunks.size();
        statusMessage = "Found " + chunksTotal + " chunks, writing...";
        sendActionBar("§e> Found " + chunksTotal + " chunks, writing...");

        for (String sub : List.of("region", "entities")) {
            Files.createDirectories(worldPath.resolve(sub));
            Files.createDirectories(worldPath.resolve("DIM-1").resolve(sub));
            Files.createDirectories(worldPath.resolve("DIM1").resolve(sub));
        }
        Files.createDirectories(worldPath.resolve("playerdata"));

        Map<String, RegionFileWriter> blockWriters = new HashMap<>();
        Map<String, RegionFileWriter> entityWriters = new HashMap<>();

        try {
            for (LevelChunkEntry entry : allChunks) {
                ChunkPos pos = entry.chunk().getPos();
                String bKey = getDimKey(entry.dimension()) + "/r." + pos.getRegionX() + "." + pos.getRegionZ();
                String eKey = getDimKey(entry.dimension()) + "_e/r." + pos.getRegionX() + "." + pos.getRegionZ();

                final Path finalWorldPath = worldPath;
                blockWriters.computeIfAbsent(bKey, k -> {
                    try { return new RegionFileWriter(getRegionDir(finalWorldPath, entry.dimension()).resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                entityWriters.computeIfAbsent(eKey, k -> {
                    try { return new RegionFileWriter(getEntityDir(finalWorldPath, entry.dimension()).resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });

                blockWriters.get(bKey).write(pos, buildChunkNbt(entry.chunk(), entry.level()));
                entityWriters.get(eKey).write(pos, buildEntityChunkNbt(entry.chunk()));

                chunksWritten++;
                if (chunksWritten % 10 == 0) {
                    String progress = "§e> Exporting chunks... §f" + chunksWritten + "§7/§f" + chunksTotal;
                    statusMessage = "Exporting chunks... " + chunksWritten + "/" + chunksTotal;
                    sendActionBar(progress);
                }
            }
        } finally {
            for (RegionFileWriter w : blockWriters.values()) w.close();
            for (RegionFileWriter w : entityWriters.values()) w.close();
        }

        writePlayerData(worldPath, mc, spawnX, spawnY, spawnZ);

        statusMessage = "Done! Saved " + chunksWritten + " chunks to: " + worldName;
        sendActionBar("§a> Export done! Saved §f" + chunksWritten + "§a chunks to: §f" + worldName);
        FlashbackExportMod.LOGGER.info("World export complete: {} chunks -> {}", chunksWritten, worldPath);
    }

    private static CompoundTag buildChunkNbt(LevelChunk wc, ServerLevel level) {
        ChunkPos pos = wc.getPos();
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", DATA_VERSION);
        chunk.putInt("xPos", pos.x);
        chunk.putInt("zPos", pos.z);
        chunk.putInt("yPos", wc.getMinSectionY());
        chunk.putString("Status", "full");
        chunk.putLong("LastUpdate", 0L);
        chunk.putLong("InhabitedTime", 0L);

        var registryAccess = level.registryAccess();
        var ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);
        var biomeReg = registryAccess.lookupOrThrow(Registries.BIOME);

        var blockCodec = PalettedContainer.codecRW(
            BlockState.CODEC,
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        );
        var biomeCodec = PalettedContainer.codecRO(
            biomeReg.holderByNameCodec(),
            Strategy.createForBiomes(biomeReg.asHolderIdMap()),
            biomeReg.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)
        );

        ListTag sections = new ListTag();
        LevelChunkSection[] sectionArray = wc.getSections();
        for (int i = 0; i < sectionArray.length; i++) {
            LevelChunkSection section = sectionArray[i];
            if (section == null || section.hasOnlyAir()) continue;

            CompoundTag sec = new CompoundTag();
            sec.putInt("Y", i + wc.getMinSectionY());

            blockCodec.encodeStart(ops, section.getStates())
                .result().ifPresent(tag -> sec.put("block_states", tag));
            biomeCodec.encodeStart(ops, section.getBiomes())
                .result().ifPresent(tag -> sec.put("biomes", tag));

            sections.add(sec);
        }
        chunk.put("sections", sections);

        ListTag blockEntities = new ListTag();
        wc.getBlockEntities().forEach((bePos, be) -> {
            try {
                CompoundTag beTag = be.saveWithFullMetadata(registryAccess);
                blockEntities.add(beTag);
            } catch (Exception ignored) {}
        });
        chunk.put("block_entities", blockEntities);
        chunk.put("Heightmaps", new CompoundTag());
        chunk.putByte("isLightOn", (byte) 1);

        return chunk;
    }

    private static CompoundTag buildEntityChunkNbt(LevelChunk wc) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", DATA_VERSION);
        ChunkPos pos = wc.getPos();
        chunk.putIntArray("Position", new int[]{pos.x, pos.z});
        chunk.put("Entities", new ListTag());
        return chunk;
    }

    // Based on SWD's createLevelDat — properly structured so Minecraft loads it directly
    private static void createLevelDat(Path worldPath, String worldName, double spawnX, double spawnY, double spawnZ, Minecraft mc) throws IOException {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", DATA_VERSION);
        data.putString("LevelName", worldName);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.putInt("version", 19133);
        data.putInt("GameType", 1);
        data.putInt("SpawnX", (int) spawnX);
        data.putInt("SpawnY", (int) spawnY);
        data.putInt("SpawnZ", (int) spawnZ);
        data.putByte("Difficulty", (byte) 1);
        data.putByte("initialized", (byte) 1);
        data.putByte("hardcore", (byte) 0);
        data.putByte("allowCommands", (byte) 1);

        CompoundTag version = new CompoundTag();
        version.putString("Name", VERSION_NAME);
        version.putInt("Id", DATA_VERSION);
        version.putString("Series", "main");
        version.putByte("Snapshot", IS_SNAPSHOT);
        data.put("Version", version);

        CompoundTag gameRules = new CompoundTag();
        gameRules.putString("doDaylightCycle", "true");
        gameRules.putString("doWeatherCycle", "true");
        data.put("GameRules", gameRules);

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        // Player spawn position
        CompoundTag player = new CompoundTag();
        player.putString("Dimension", "minecraft:overworld");
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(spawnX));
        pos.add(DoubleTag.valueOf(spawnY));
        pos.add(DoubleTag.valueOf(spawnZ));
        player.put("Pos", pos);
        if (mc.player != null) {
            ListTag rot = new ListTag();
            rot.add(FloatTag.valueOf(mc.player.getYRot()));
            rot.add(FloatTag.valueOf(mc.player.getXRot()));
            player.put("Rotation", rot);
        }
        data.put("Player", player);

        CompoundTag worldGenSettings = new CompoundTag();
        worldGenSettings.putLong("seed", 0L);
        worldGenSettings.putByte("generate_features", (byte) 0);
        worldGenSettings.putByte("bonus_chest", (byte) 0);

        CompoundTag dimensions = new CompoundTag();

        CompoundTag overworld = new CompoundTag();
        overworld.putString("type", "minecraft:overworld");
        CompoundTag overworldGen = new CompoundTag();
        overworldGen.putString("type", "minecraft:flat");
        CompoundTag overworldSettings = new CompoundTag();
        overworldSettings.put("layers", new ListTag());
        overworldSettings.putString("biome", "minecraft:plains");
        overworldSettings.putByte("structure", (byte) 0);
        overworldGen.put("settings", overworldSettings);
        overworld.put("generator", overworldGen);
        dimensions.put("minecraft:overworld", overworld);

        CompoundTag nether = new CompoundTag();
        nether.putString("type", "minecraft:the_nether");
        CompoundTag netherGen = new CompoundTag();
        netherGen.putString("type", "minecraft:noise");
        netherGen.putString("settings", "minecraft:nether");
        CompoundTag netherBiome = new CompoundTag();
        netherBiome.putString("type", "minecraft:multi_noise");
        netherBiome.putString("preset", "minecraft:nether");
        netherGen.put("biome_source", netherBiome);
        nether.put("generator", netherGen);
        dimensions.put("minecraft:the_nether", nether);

        CompoundTag end = new CompoundTag();
        end.putString("type", "minecraft:the_end");
        CompoundTag endGen = new CompoundTag();
        endGen.putString("type", "minecraft:noise");
        endGen.putString("settings", "minecraft:end");
        CompoundTag endBiome = new CompoundTag();
        endBiome.putString("type", "minecraft:the_end");
        endGen.put("biome_source", endBiome);
        end.put("generator", endGen);
        dimensions.put("minecraft:the_end", end);

        worldGenSettings.put("dimensions", dimensions);
        data.put("WorldGenSettings", worldGenSettings);

        CompoundTag dragonFight = new CompoundTag();
        dragonFight.putByte("NeedsStateScanning", (byte) 0);
        dragonFight.putByte("DragonKilled", (byte) 1);
        dragonFight.putByte("PreviouslyKilled", (byte) 1);
        dragonFight.putByte("IsRespawning", (byte) 0);
        data.put("DragonFight", dragonFight);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, worldPath.resolve("level.dat"));

        Files.write(worldPath.resolve("session.lock"),
                ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array());
    }

    private static void writePlayerData(Path worldPath, Minecraft mc, double spawnX, double spawnY, double spawnZ) {
        if (mc.player == null) return;
        try {
            CompoundTag p = new CompoundTag();
            p.putInt("DataVersion", DATA_VERSION);
            p.putInt("playerGameType", 1);
            p.putFloat("Health", mc.player.getHealth());

            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(spawnX));
            pos.add(DoubleTag.valueOf(spawnY));
            pos.add(DoubleTag.valueOf(spawnZ));
            p.put("Pos", pos);

            ListTag rot = new ListTag();
            rot.add(FloatTag.valueOf(mc.player.getYRot()));
            rot.add(FloatTag.valueOf(mc.player.getXRot()));
            p.put("Rotation", rot);

            p.putString("Dimension", "minecraft:overworld");
            p.putIntArray("UUID", new int[]{0, 0, 0, 0});
            p.put("Inventory", new ListTag());
            p.putInt("SelectedItemSlot", 0);

            NbtIo.writeCompressed(p, worldPath.resolve("playerdata").resolve(mc.player.getUUID() + ".dat"));
        } catch (IOException e) {
            FlashbackExportMod.LOGGER.warn("Failed to write player data", e);
        }
    }

    private static void sendActionBar(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.execute(() -> mc.gui.setOverlayMessage(
                net.minecraft.network.chat.Component.literal(message), false
            ));
        }
    }

    private static String getDimKey(ResourceKey<Level> d) {
        if (d == Level.NETHER) return "nether";
        if (d == Level.END) return "end";
        return "overworld";
    }

    private static Path getRegionDir(Path worldPath, ResourceKey<Level> d) {
        if (d == Level.NETHER) return worldPath.resolve("DIM-1").resolve("region");
        if (d == Level.END) return worldPath.resolve("DIM1").resolve("region");
        return worldPath.resolve("region");
    }

    private static Path getEntityDir(Path worldPath, ResourceKey<Level> d) {
        if (d == Level.NETHER) return worldPath.resolve("DIM-1").resolve("entities");
        if (d == Level.END) return worldPath.resolve("DIM1").resolve("entities");
        return worldPath.resolve("entities");
    }

    private record LevelChunkEntry(LevelChunk chunk, ResourceKey<Level> dimension, ServerLevel level) {}
}
