package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import net.itemfinder.main.mixin.RegionBasedStorageMixin;
import net.itemfinder.main.mixin.RegionFileMixin;
import net.itemfinder.main.mixin.StorageIOWorkerMixin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.player.PlayerEntity;
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
import java.util.*;
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
    static int chunkCount;
    static int searchType;
    static String searchString = "";
    static PlayerEntity player;

    static final AtomicInteger blockCount = new AtomicInteger(0);
    static final AtomicInteger entityCount = new AtomicInteger(0);

    static final List<BlockPos> coordinates = new ArrayList<>();
    static int currentPosition = 1;

    private static Thread getThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName("Item-Finder-Scan-Worker-" + threadCount++);
        thread.setDaemon(true);
        return thread;
    }

    public static List<Long> getChunkPositions(ServerWorld world) {
        RegionBasedStorageMixin storage = (RegionBasedStorageMixin) (Object)
                ((StorageIOWorkerMixin) world.getChunkManager().threadedAnvilChunkStorage.getWorker()).getStorage();
        assert storage != null;

        List<Long> positions = new ArrayList<>();
        try {
            for (File file : storage.getDirectory().toFile().listFiles()) {
                RegionFile regionFile = new RegionFile(file.toPath(), storage.getDirectory(), storage.getDsync());
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

    @SuppressWarnings("SameReturnValue")
    public static int confirm() {
        if (itemSearchRequested) ItemFinder.globalSearch();
        else if (lootTableSearchRequested) LootTableFinder.globalSearch();
        return 1;
    }

    public static void teleportToNext() {
        //noinspection StatementWithEmptyBody
        while (IFMod.teleportKey.wasPressed());
        ClientPlayNetworkHandler nh = MinecraftClient.getInstance().getNetworkHandler();
        if (nh == null || player == null || !(player.isCreative() || player.isSpectator())) return;

        if (coordinates.isEmpty() || currentPosition > coordinates.size()) {
            player.sendMessage(Text.of("No search results in teleport queue!"));
            return;
        }
        BlockPos pos = coordinates.get(currentPosition - 1);
        nh.sendCommand("tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        player.sendMessage(Text.literal("Teleporting to result " + currentPosition + "/" + coordinates.size())
                .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)), true);
        currentPosition++;
    }

    @SuppressWarnings("SameReturnValue")
    public static int stop(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = Objects.requireNonNull(context.getSource().getPlayer());
        player.sendMessage(Text.of(searching.get() ? "Search interrupted." : "why... search wasn't running..."));

        reset();
        return 1;
    }

    public static void reset() {
        searching.set(false);
        blockCount.set(0);
        entityCount.set(0);
        coordinates.clear();
        ItemFinder.results.forEach(result -> coordinates.add(result.pos()));
        LootTableFinder.results.forEach(result -> coordinates.add(result.pos()));
        currentPosition = 1;
        ItemFinder.results.clear();
        LootTableFinder.results.clear();
    }
}
