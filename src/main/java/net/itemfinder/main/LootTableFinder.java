package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.*;
import net.minecraft.block.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTables;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.itemfinder.main.Controller.*;

public class LootTableFinder {

    static final Set<SearchResult> results = Collections.synchronizedSet(new HashSet<>());

    /**
     * Runs a normal loot table search. Called via `/finditem loot_table` without global modifier.
     */
    @SuppressWarnings("SameReturnValue")
    public static int search(String s, CommandContext<ServerCommandSource> context) {
        player = context.getSource().getPlayer();
        if (searching.get()) {
            player.sendMessage(Text.of("Search already in progress..."));
            return 1;
        }
        ServerWorld world = context.getSource().getWorld();

        //find matches in all loaded lootable block entities
        ((ServerChunkLoadingManagerMixin) world.getChunkManager().chunkLoadingManager)
                .entryIterator().forEach(chunkHolder -> {
                    WorldChunk chunk = chunkHolder.getWorldChunk();
                    if (chunk != null) chunk.getBlockEntities().values().forEach(be -> checkBlockEntity(
                            chunk.getBlockState(be.getPos()).getBlock().getName().getString(), be, s));
                });

        sendResults(Objects.requireNonNull(context.getSource().getPlayer()));
        return 1;
    }

    /**
     * Prepares a global item search. Called via `/finditem loot_table` with global modifier.
     * Asks for confirmation according to current config.
     */
    @SuppressWarnings("SameReturnValue")
    public static int prepareGlobalSearch(String s, CommandContext<ServerCommandSource> context) {
        if (searching.get()) {
            Objects.requireNonNull(context.getSource().getPlayer()).sendMessage(Text.of("Search already in progress..."));
            return 1;
        }

        //Set all scan parameters
        searchString = s.toLowerCase();
        player = Objects.requireNonNull(context.getSource().getPlayer());
        lootTableSearchRequested = true;

        if (IFConfig.INSTANCE.autoConfirm) globalSearch();
        else {
            player.sendMessage(Text.of("Starting a full-world scan. Are you sure?"));
            player.sendMessage(Text.literal("[Start]").setStyle(Style.EMPTY
                    .withColor(Formatting.AQUA)
                    .withUnderline(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/finditem confirm"))));
        }
        return 1;
    }

    /**
     * Runs a global item search.
     */
    @SuppressWarnings("SameReturnValue")
    public static void globalSearch() {
        if (!lootTableSearchRequested) {
            if (player != null) player.sendMessage(Text.of("nothing to confirm..."));
            return;
        }
        lootTableSearchRequested = false;
        searching.set(true);

        long start = System.nanoTime();

        scanExecutor.submit(() -> {
            ServerWorld world = (ServerWorld) player.getWorld();
            List<Long> chunkPositions = getChunkPositions(world);

            chunkCount = chunkPositions.size();
            player.sendMessage(Text.of("Checking " + chunkCount + " chunks..."));
            AtomicInteger progress = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

            //Check loaded chunks first to avoid loading their data again, remove their positions from queue.
            ((ServerChunkLoadingManagerMixin) world.getChunkManager().chunkLoadingManager)
                    .entryIterator().forEach(chunkHolder -> {
                        WorldChunk chunk = chunkHolder.getWorldChunk();
                        if (chunk == null || !chunkHolder.isAccessible()) return;

                        chunk.getBlockEntities().values()
                                .forEach(be -> checkBlockEntity(chunk.getBlockState(be.getPos()).getBlock().getName().getString(), be, searchString));
                        chunkPositions.remove(chunk.getPos().toLong());
                        progress.incrementAndGet();
                    });

            //Iterating through all generated, unloaded chunks, extracting their block entity data.
            for (Long position : chunkPositions) {
                if (!searching.get()) {
                    sendResults(player);
                    break;
                }
                ChunkPos pos = new ChunkPos((int) (position >> 32), position.intValue());

                CompletableFuture<Void> future = new CompletableFuture<>();
                futures.add(future);

                CompletableFuture<Optional<NbtCompound>> chunkNBT = world.getChunkManager().chunkLoadingManager.getNbt(pos);
                scanExecutor.submit(() -> {
                    progress.incrementAndGet();
                    Optional<NbtCompound> compound;
                    try {
                        compound = chunkNBT.join();
                    }
                    catch (Exception e) {
                        future.complete(null);
                        return;
                    }
                    if (!searching.get() || compound.isEmpty()) {
                        future.complete(null);
                        return;
                    }

                    NbtCompound nbtData = compound.get();
                    try {
                        if (!searching.get()) {
                            future.complete(null);
                            return;
                        }
                        nbtData.getList("block_entities", 10).forEach(nbtElement -> checkBlockEntityNBT((NbtCompound) nbtElement));
                    }
                    catch (Throwable e) {
                        IFMod.LOGGER.error("Failed to deserialize chunk {} with data of size {}", pos, nbtData.getSize());
                        future.complete(null);
                        throw e;
                    }
                    player.sendMessage(Text.literal("Progress: " + progress.get() + "/" + chunkCount + ".")
                            .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)), true);
                    future.complete(null);
                });
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                sendResults(player);
                player.sendMessage(Text.literal("Finished in " + (System.nanoTime() - start) / 1000000000 + "s.")
                        .setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
                searching.set(false);
            }
            catch (Throwable e) {
                searching.set(false);
                IFMod.LOGGER.error("Scan crashed!! Congratulations", e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Adds a new entry to {@link #results} if given block entity fits the search parameter.
     */
    public static void checkBlockEntity(String name, BlockEntity be, String s) {
        blockCount.incrementAndGet();

        if (!(be instanceof LootableContainerBlockEntity)) return;
        RegistryKey<LootTable> lootTableKey = ((LootableContainerBlockEntityMixin) be).getLootTable();
        switch (s) {
            //Check if block entity has any loot table
            case "any" -> {
                if (lootTableKey != null)
                    results.add(new SearchResult(name, be.getPos(), lootTableKey.getValue().getPath()));
            }
            //Check if block entity doesn't have a loot table
            case "none" -> {
                if (lootTableKey == null)
                    results.add(new SearchResult(name, be.getPos(), ""));
            }
            //Check if block entity doesn't have a loot table and has an empty inventory
            case "none_empty" -> {
                if (lootTableKey == null && ((LootableContainerBlockEntity) be).isEmpty())
                    results.add(new SearchResult(name, be.getPos(), ""));
            }
            //Check if block entity has the right loot table
            default -> {
                if (lootTableKey != null && lootTableKey.getValue().getPath().equals(s))
                    results.add(new SearchResult(name, be.getPos(), lootTableKey.getValue().getPath()));
            }
        }
    }

    /**
     * Adds a new entry to {@link #results} if given block entity (in NBT form) fits the search parameter.
     */
    public static void checkBlockEntityNBT(NbtCompound nbt) {
        blockCount.incrementAndGet();

        //minecraft:trapped_chest -> trapped_chest
        String id = nbt.getString("id").split(":")[1];

        if (!(id.equals("chest") || id.equals("barrel") || id.equals("dispenser") || id.equals("dropper")
                || id.equals("hopper") || id.contains("shulker_box") || id.equals("trapped_chest"))) return;

        //trapped_chest -> Trapped Chest
        String name = Arrays.stream(id.split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
        BlockPos pos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));

        //See checkBlockEntity() for branch descriptions
        switch (searchString) {
            case "any" -> {
                if (nbt.contains("LootTable"))
                    results.add(new SearchResult(name, pos, nbt.getString("LootTable")));
            }
            case "none" -> {
                if (!nbt.contains("LootTable"))
                    results.add(new SearchResult(name, pos, nbt.getString("")));
            }
            case "none_empty" -> {
                if (!nbt.contains("LootTable") && nbt.getList("Items", 10).isEmpty())
                    results.add(new SearchResult(name, pos, ""));
            }
            default -> {
                if (nbt.contains("LootTable") && nbt.getString("LootTable").split(":")[1].equals(searchString))
                    results.add(new SearchResult(name, pos, ""));
            }
        }
    }

    /**
     * Prints out search results with search stats & teleportation commands.
     */
    public static void sendResults(PlayerEntity player) {
        player.sendMessage(Text.of("/-----------------------------/"));
        player.sendMessage(Text.of("Blocks searched: " + blockCount));
        player.sendMessage(Text.of("Matching results: " + results.size() +
                (results.isEmpty() ? " :(" : "")));

        //format: 1. <block name> [x, y, z]
        int i = 0;
        for (SearchResult result : results) player.sendMessage(makeMessage(++i, result.name(), result.pos(), result.lootTable));
        player.sendMessage(Text.of("/-----------------------------/"));

        reset();
    }

    /**
     * Used to make formatted lines for each search result entry.
     */
    public static Text makeMessage(int i, String name, BlockPos pos, String lootTable) {
        MutableText text = Text.literal((i) + ". ");
        if (!lootTable.isEmpty()) {
            text.append(Text.literal(name)).setStyle(Style.EMPTY
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.of(lootTable))));
        }
        else text.append(Text.literal(name));
        text.append(Text.literal(" "))
                .append(Text.literal("[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "]")
                        .setStyle(Style.EMPTY
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.of("Click to teleport")))
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                                .withColor(Formatting.AQUA)
                                .withUnderline(true)));
        return text;
    }

    /**
     * Returns item IDs for `/finditem loot_table` autocompletion. Vanilla loot tables are included according to current config.
     */
    @SuppressWarnings("unused")
    public static CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String input = builder.getInput().toLowerCase().replace("/finditem loot_table ", "").replace(" global", "").replace("\"", "");

        if ("any".contains(input)) builder.suggest("any");
        if ("none".contains(input)) builder.suggest("none");
        if ("none_empty".contains(input)) builder.suggest("none_empty");

        LootTables.getAll().forEach(registryKey -> {
            String name = registryKey.getValue().getPath();
            if (!name.contains(input)) return;
            if (name.startsWith("archaeology") || name.startsWith("blocks") || name.startsWith("entities")
                    || name.startsWith("equipment") || name.startsWith("gameplay") || name.startsWith("pots")
                    || name.startsWith("shearing") || name.startsWith("spawners")) return;
            if (!IFConfig.INSTANCE.suggestVanillaLootTables && (name.startsWith("chests") || name.startsWith("dispensers"))) return;
            builder.suggest("\"" + name + "\"");
        });
        return builder.buildFuture();
    }

    public record SearchResult(String name, BlockPos pos, String lootTable) {

    }
}
