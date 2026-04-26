package com.moulberry.flashback.export;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class WorldExporter {

    private static final int DATA_VERSION = 3953;
    private static final String VERSION_NAME = "1.21.10";

    public static volatile boolean isExporting = false;
    public static volatile String statusMessage = "";
    public static volatile int chunksWritten = 0;
    public static volatile int chunksTotal = 0;

    public static void startExport() {
        if (isExporting) return;

        var replayServer = Flashback.getReplayServer();
        if (replayServer == null) {
            Flashback.LOGGER.error("Cannot export: not in a replay");
            return;
        }

        isExporting = true;
        chunksWritten = 0;
        chunksTotal = 0;
        statusMessage = "Starting export...";

        Thread exportThread = new Thread(() -> {
            try {
                doExport(replayServer);
            } catch (Exception e) {
                Flashback.LOGGER.error("World export failed", e);
                statusMessage = "Export failed: " + e.getMessage();
            } finally {
                isExporting = false;
            }
        }, "flashback-world-exporter");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    private static void doExport(net.minecraft.client.server.IntegratedServer server) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Path savesDir = mc.gameDirectory.toPath().resolve("saves");
        String worldName = "Flashback_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path worldPath = savesDir.resolve(worldName);
        Files.createDirectories(worldPath);

        statusMessage = "Creating world structure...";
        createLevelDat(worldPath, worldName);

        // Collect all loaded chunks from all dimensions
        List<LevelChunkEntry> allChunks = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            try {
                var chunkMap = level.getChunkSource().chunkMap;
                // Access the internal chunk holder map via reflection
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
                Flashback.LOGGER.warn("Failed to iterate chunks for {}", dimension.location(), e);
            }
        }

        chunksTotal = allChunks.size();
        statusMessage = "Found " + chunksTotal + " chunks, writing...";

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

                blockWriters.computeIfAbsent(bKey, k -> {
                    try { return new RegionFileWriter(getRegionDir(worldPath, entry.dimension()).resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                entityWriters.computeIfAbsent(eKey, k -> {
                    try { return new RegionFileWriter(getEntityDir(worldPath, entry.dimension()).resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });

                blockWriters.get(bKey).write(pos, buildChunkNbt(entry.chunk(), entry.level()));
                entityWriters.get(eKey).write(pos, buildEntityChunkNbt(entry.chunk()));

                chunksWritten++;
                if (chunksWritten % 50 == 0) {
                    statusMessage = "Writing chunks... " + chunksWritten + "/" + chunksTotal;
                }
            }
        } finally {
            for (RegionFileWriter w : blockWriters.values()) w.close();
            for (RegionFileWriter w : entityWriters.values()) w.close();
        }

        writePlayerData(worldPath, mc);
        statusMessage = "Done! Saved " + chunksWritten + " chunks to: " + worldName;
        Flashback.LOGGER.info("World export complete: {} chunks -> {}", chunksWritten, worldPath);
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

        // Use Strategy directly as confirmed in BlockOverrideKeyframe.java
        var blockCodec = PalettedContainer.codecRW(
            BlockState.CODEC,
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        );

        // getBiomes() returns PalettedContainerRO, use codecRO
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

    private static void createLevelDat(Path worldPath, String worldName) throws IOException {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", DATA_VERSION);
        data.putString("LevelName", worldName);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.putInt("version", 19133);
        data.putInt("GameType", 1);
        data.putInt("SpawnX", 0); data.putInt("SpawnY", 64); data.putInt("SpawnZ", 0);
        data.putByte("Difficulty", (byte) 1);
        data.putByte("initialized", (byte) 1);
        data.putByte("hardcore", (byte) 0);
        data.putByte("allowCommands", (byte) 1);

        CompoundTag version = new CompoundTag();
        version.putString("Name", VERSION_NAME);
        version.putInt("Id", DATA_VERSION);
        version.putString("Series", "main");
        version.putByte("Snapshot", (byte) 0);
        data.put("Version", version);

        CompoundTag gameRules = new CompoundTag();
        gameRules.putString("doDaylightCycle", "true");
        data.put("GameRules", gameRules);

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, worldPath.resolve("level.dat"));
        Files.write(worldPath.resolve("session.lock"),
                ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array());
    }

    private static void writePlayerData(Path worldPath, Minecraft mc) {
        if (mc.player == null) return;
        try {
            CompoundTag p = new CompoundTag();
            p.putInt("DataVersion", DATA_VERSION);
            p.putInt("playerGameType", 1);
            p.putFloat("Health", mc.player.getHealth());
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(mc.player.getX()));
            pos.add(DoubleTag.valueOf(mc.player.getY()));
            pos.add(DoubleTag.valueOf(mc.player.getZ()));
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
            Flashback.LOGGER.warn("Failed to write player data", e);
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
