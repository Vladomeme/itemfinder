package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.*;
import net.minecraft.block.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.loot.LootDataType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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

    @SuppressWarnings("SameReturnValue")
    public static int search(String s, CommandContext<ServerCommandSource> context) {
        player = context.getSource().getPlayer();
        if (searching.get()) {
            player.sendMessage(Text.of("Search already in progress..."));
            return 1;
        }
        ServerWorld world = context.getSource().getWorld();

        //find matches in all loaded block entities with storage
        ((ThreadedAnvilChunkStorageMixin) world.getChunkManager().threadedAnvilChunkStorage)
                .entryIterator().forEach(chunkHolder -> {
                    WorldChunk chunk = chunkHolder.getWorldChunk();
                    if (chunk != null) chunk.getBlockEntities().values().forEach(be -> checkBlockEntity(
                            chunk.getBlockState(be.getPos()).getBlock().getName().getString(), be, s));
                });

        //send results to the player who executed the command
        sendResults(Objects.requireNonNull(context.getSource().getPlayer()));
        return 1;
    }

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

            ((ThreadedAnvilChunkStorageMixin) world.getChunkManager().threadedAnvilChunkStorage)
                    .entryIterator().forEach(chunkHolder -> {
                        WorldChunk chunk = chunkHolder.getWorldChunk();
                        if (chunk == null || !chunkHolder.isAccessible()) return;

                        chunk.getBlockEntities().values()
                                .forEach(be -> checkBlockEntity(chunk.getBlockState(be.getPos()).getBlock().getName().getString(), be, searchString));
                        chunkPositions.remove(chunk.getPos().toLong());
                        progress.incrementAndGet();
                    });

            for (Long position : chunkPositions) {
                if (!searching.get()) {
                    sendResults(player);
                    break;
                }
                ChunkPos pos = new ChunkPos((int) (position >> 32), position.intValue());

                CompletableFuture<Void> future = new CompletableFuture<>();
                futures.add(future);

                CompletableFuture<Optional<NbtCompound>> chunkNBT = world.getChunkManager().threadedAnvilChunkStorage.getNbt(pos);
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

    public static void checkBlockEntity(String beName, BlockEntity be, String s) {
        blockCount.incrementAndGet();

        if (!(be instanceof LootableContainerBlockEntity)) return;
        Identifier lootTable = ((LootableContainerBlockEntityMixin) be).getLootTable();
        switch (s) {
            case "any" -> {
                if (lootTable != null)
                    results.add(new SearchResult(beName, be.getPos(), lootTable.getPath()));
            }
            case "none" -> {
                if (lootTable == null)
                    results.add(new SearchResult(beName, be.getPos(), ""));
            }
            case "none_empty" -> {
                if (lootTable == null && ((LootableContainerBlockEntity) be).isEmpty())
                    results.add(new SearchResult(beName, be.getPos(), ""));
            }
            default -> {
                if (lootTable != null && lootTable.getPath().equals(s))
                    results.add(new SearchResult(beName, be.getPos(), lootTable.getPath()));
            }
        }
    }
    public static void checkBlockEntityNBT(NbtCompound nbt) {
        blockCount.incrementAndGet();

        String id = nbt.getString("id").split(":")[1];
        if (!(id.equals("chest") || id.equals("barrel") || id.equals("dispenser") || id.equals("dropper")
                || id.equals("hopper") || id.contains("shulker_box") || id.equals("trapped_chest"))) return;

        String name = Arrays.stream(id.split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
        BlockPos pos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));

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

    public static void sendResults(PlayerEntity player) {
        player.sendMessage(Text.of("/-----------------------------/"));
        //send results with teleportation commands
        player.sendMessage(Text.of("Blocks searched: " + blockCount));
        player.sendMessage(Text.of("Matching results: " + results.size() +
                (results.isEmpty() ? " :(" : "")));

        //format: 1. <block name> [x, y, z]
        int i = 0;
        for (SearchResult result : results) player.sendMessage(makeMessage(++i, result.name(), result.pos(), result.lootTable));
        player.sendMessage(Text.of("/-----------------------------/"));

        reset();
    }

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

    public static CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String input = builder.getInput().toLowerCase().replace("/finditem loot_table ", "").replace(" global", "").replace("\"", "");

        if ("any".contains(input)) builder.suggest("any");
        if ("none".contains(input)) builder.suggest("none");
        if ("none_empty".contains(input)) builder.suggest("none_empty");

        context.getSource().getServer().getLootManager().getIds(LootDataType.LOOT_TABLES).forEach(id -> {
                    String name = id.getPath();
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
