package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.LootableContainerBlockEntityMixin;
import net.itemfinder.main.mixin.ServerChunkLoadingManagerMixin;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
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
    static final List<String> lootTables = new ArrayList<>();

    /**
     * Runs a normal loot table search. Called via `/finditem loot_table` without global modifier.
     */
    @SuppressWarnings("SameReturnValue")
    public static int search(String s, CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity source = getSourcePlayer(context);
        if (searching.get()) {
            source.sendMessage(Text.of("Search already in progress..."));
            return 1;
        }
        currentUser = source;
        ServerWorld world = context.getSource().getWorld();

        //find matches in all loaded lootable block entities
        ((ServerChunkLoadingManagerMixin) world.getChunkManager().chunkLoadingManager)
                .entryIterator().forEach(chunkHolder -> {
                    WorldChunk chunk = chunkHolder.getWorldChunk();
                    if (chunk != null) chunk.getBlockEntities().values().forEach(be -> checkBlockEntity(
                            chunk.getBlockState(be.getPos()).getBlock().getName().getString(), be, s));
                });

        sendResults();
        return 1;
    }

    /**
     * Prepares a global item search. Called via `/finditem loot_table` with global modifier.
     * Asks for confirmation according to current config.
     */
    @SuppressWarnings("SameReturnValue")
    public static int prepareGlobalSearch(String s, CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        if (searching.get()) {
            getSourcePlayer(context).sendMessage(
                    Text.of("Search is already active (" + (System.nanoTime() - startTime) / 1000000000
                            + "s., requested by " + currentUser.getGameProfile().getName()));
            return 1;
        }

        //Set all scan parameters
        searchString = s.toLowerCase();
        currentUser = getSourcePlayer(context);
        lootTableSearchRequested = true;

        if (IFConfig.INSTANCE.autoConfirm) globalSearch();
        else {
            currentUser.sendMessage(Text.of("Starting a full-world scan. Are you sure?"));
            currentUser.sendMessage(Text.literal("[Start]").setStyle(Style.EMPTY
                    .withColor(Formatting.AQUA)
                    .withUnderline(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/finditem confirm"))));
        }
        return 1;
    }

    /**
     * Runs a global item search by retrieving positions of all generated chunks and reading their NBT data.
     */
    @SuppressWarnings("SameReturnValue")
    public static void globalSearch() {
        lootTableSearchRequested = false;
        searching.set(true);

        startTime = System.nanoTime();

        scanExecutor.submit(() -> {
            ServerWorld world = (ServerWorld) currentUser.getWorld();
            List<Long> chunkPositions = getChunkPositions(world);

            chunkCount = chunkPositions.size();
            currentUser.sendMessage(Text.of("Checking " + chunkCount + " chunks..."));
            AtomicInteger progress = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

            //Iterating through all generated chunks, extracting their block entity data.
            for (Long position : chunkPositions) {
                if (!searching.get()) {
                    sendResults();
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
                        IFMod.LOGGER.error("Failed to deserialize chunk {} with data of size {}. Ignore if search finishes.", pos, nbtData.getSize());
                        future.complete(null);
                        throw e;
                    }
                    currentUser.sendMessage(Text.literal("Progress: " + progress.get() + "/" + chunkCount + ".")
                            .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)), true);
                    future.complete(null);
                });
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                sendResults();
                currentUser.sendMessage(Text.literal("Finished in " + (System.nanoTime() - startTime) / 1000000000 + "s.")
                        .setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
                searching.set(false);
            }
            catch (Throwable e) {
                searching.set(false);
                IFMod.LOGGER.error("Scan crashed!! Congratulations :)", e);
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
        if (IFConfig.INSTANCE.onlyShowChestsLootTable && !(be instanceof ChestBlockEntity)) return;
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

        if (IFConfig.INSTANCE.onlyShowChestsLootTable) {
            if (!(id.equals("chest"))) return;
        }
        else if (!(id.equals("chest") || id.equals("barrel") || id.equals("dispenser") || id.equals("dropper")
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
                if (nbt.contains("LootTable")) {
                    String lootTable = nbt.getString("LootTable");
                    if (lootTable.substring(lootTable.indexOf(':') + 1).equals(searchString))
                        results.add(new SearchResult(name, pos, ""));
                }
            }
        }
    }

    /**
     * Prints out search results with search stats & teleportation commands.
     */
    public static void sendResults() {
        currentUser.sendMessage(Text.of("/-----------------------------/"));
        currentUser.sendMessage(Text.of("Blocks searched: " + blockCount));
        currentUser.sendMessage(Text.of("Matching results: " + results.size() +
                (results.isEmpty() ? " :(" : "")));

        //format: 1. <block name> [x, y, z]
        int i = 0;
        for (SearchResult result : results) currentUser.sendMessage(makeMessage(++i, result.name(), result.pos(), result.lootTable));
        currentUser.sendMessage(Text.of("/-----------------------------/"));

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
     * Returns loot table paths for `/finditem loot_table` autocompletion. Vanilla loot tables are included according to current config.
     */
    @SuppressWarnings("unused")
    public static CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String input = builder.getInput().toLowerCase().replace("/finditem loot_table ", "").replace(" global", "").replace("\"", "");

        for (String s : lootTables) {
            if (s.contains(input)) builder.suggest(s);
        }

        return builder.buildFuture();
    }

    public static void updateSuggestions(ResourceManager rm) {
        lootTables.clear();

        lootTables.add("any");
        lootTables.add("none");
        lootTables.add("none_empty");

        rm.findAllResources("loot_table", id -> {
            String path = id.getPath();
            String name = path.substring(11, path.length() - 5);
            if (name.startsWith("archaeology") || name.startsWith("blocks") || name.startsWith("entities")
                    || name.startsWith("equipment") || name.startsWith("gameplay") || name.startsWith("pots")
                    || name.startsWith("shearing") || name.startsWith("spawners") || name.startsWith("dispensers")) return false;
            //noinspection RedundantIfStatement
            if (!IFConfig.INSTANCE.suggestVanillaLootTables && name.startsWith("chests")) return false;
            return true;
        }).forEach((id, resources) -> {
            String path = id.getPath();
            lootTables.add("\"" + path.substring(11, path.length() - 5) + "\"");
        });
    }

    public record SearchResult(String name, BlockPos pos, String lootTable) {

    }
}
