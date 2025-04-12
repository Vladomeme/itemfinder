package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.itemfinder.main.mixin.RegionBasedStorageMixin;
import net.itemfinder.main.mixin.RegionFileMixin;
import net.itemfinder.main.mixin.StorageIOWorkerMixin;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.storage.RegionFile;

import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Controller {

    static int threadCount = 0;
    static final ExecutorService scanExecutor = Executors.newFixedThreadPool(4, Controller::getThread);

    static boolean itemSearchRequested = false;
    static boolean lootTableSearchRequested = false;
    static final AtomicBoolean searching = new AtomicBoolean(false);
    static long startTime;
    static int chunkCount;
    static int searchType;
    static String searchString = "";
    static ServerPlayerEntity currentUser;

    static final AtomicInteger blockCount = new AtomicInteger(0);
    static final AtomicInteger entityCount = new AtomicInteger(0);

    static final Map<String, List<BlockPos>> coordinates = new HashMap<>();
    static final Map<String, Integer> currentPositions = new HashMap<>();

    /**
     * Used to create threads for the {@link #scanExecutor}.
     */
    private static Thread getThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName("Item-Finder-Scan-Worker-" + threadCount++);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Retrieves the positions of all generated chunks for the current dimension by reading world's region files.
     */
    public static List<Long> getChunkPositions(ServerWorld world) {
        RegionBasedStorageMixin storage = (RegionBasedStorageMixin) (Object)
                ((StorageIOWorkerMixin) world.getChunkManager().chunkLoadingManager.getWorker()).getStorage();
        assert storage != null;

        List<Long> positions = new ArrayList<>();
        try {
            for (File file : storage.getDirectory().toFile().listFiles()) {
                RegionFile regionFile = new RegionFile(storage.getStorageKey(), file.toPath(), storage.getDirectory(), storage.getDsync());
                IntBuffer buffer = ((RegionFileMixin) regionFile).getSectorData().duplicate();

                String[] split = file.getName().split("\\.", 4);
                int baseX = Integer.parseInt(split[1]) * 32;
                int baseZ = Integer.parseInt(split[2]) * 32;

                for (int i = 0; i < 1024; i++) {
                    if (buffer.get(i) != 0)
                        positions.add((long) (baseX + (i % 32)) << 32 | (long) (baseZ + (i / 32)) << 32 >>> 32);
                }
                regionFile.close();
            }
            return positions;
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Called via command `/finditem confirm`, used to start global search when confirmation is required by current config.
     */
    @SuppressWarnings("SameReturnValue")
    public static int confirm(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity source = getSourcePlayer(context);
        if (source != currentUser) source.sendMessage(Text.of("Current search request was made by another player."));

        if (itemSearchRequested) ItemFinder.globalSearch();
        else if (lootTableSearchRequested) LootTableFinder.globalSearch();
        else source.sendMessage(Text.of("No search request found."));
        return 1;
    }

    /**
     * Teleports the player to next search result. Called using `/finditem next`.
     * Search results are stored in {@link #coordinates}, current position - {@link #currentPositions}.
     */
    @SuppressWarnings("SameReturnValue")
    public static int teleportToNext(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = getSourcePlayer(context);
        if (!(player.isCreative() || player.isSpectator())) {
            throw new SimpleCommandExceptionType(Text.of("Command can only be used in creative and spectator mode.")).create();
        }
        String playerName = player.getGameProfile().getName();
        List<BlockPos> playerCoordinates = coordinates.get(playerName);
        Integer currentPosition = currentPositions.get(playerName);
        if (playerCoordinates == null || playerCoordinates.isEmpty() || currentPosition > playerCoordinates.size()) {
            player.sendMessage(Text.of("No search results in teleport queue!"));
            return 1;
        }
        BlockPos pos = playerCoordinates.get(currentPosition - 1);
        player.requestTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.sendMessage(Text.literal("Teleporting to result " + currentPosition + "/" + playerCoordinates.size())
                .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)), true);
        currentPositions.merge(playerName, 1, Integer::sum);
        return 1;
    }

    /**
     * Used to stop running global search. Called via command `/finditem stop`.
     */
    @SuppressWarnings("SameReturnValue")
    public static int stop(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = getSourcePlayer(context);
        player.sendMessage(Text.of(searching.get() ? "Search interrupted." : "why... search wasn't running..."));

        reset();
        return 1;
    }

    /**
     * Called at the end of each search request. Clears all parameters and records search results to {@link #coordinates}.
     */
    public static void reset() {
        searching.set(false);
        blockCount.set(0);
        entityCount.set(0);
        itemSearchRequested = false;
        lootTableSearchRequested = false;

        String playerName = currentUser.getGameProfile().getName();
        List<BlockPos> playerCoordinates = coordinates.get(playerName);
        if (playerCoordinates == null) {
            playerCoordinates = new ArrayList<>();
            coordinates.put(playerName, playerCoordinates);
        }
        else playerCoordinates.clear();
        for (ItemFinder.SearchResult result : ItemFinder.results) playerCoordinates.add(result.pos());
        for (LootTableFinder.SearchResult result : LootTableFinder.results) playerCoordinates.add(result.pos());
        currentPositions.put(playerName, 1);

        ItemFinder.results.clear();
        LootTableFinder.results.clear();
    }

    /**
     * Called at game shutdown to remove executor threads. (I don't know if they're removed automatically without this).
     */
    public static void shutdown() {
        scanExecutor.shutdown();
    }

    /**
     * Makes sure the commands are called by a player and returns that player from command context.
     */
    public static ServerPlayerEntity getSourcePlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return context.getSource().getPlayerOrThrow();
    }
}
