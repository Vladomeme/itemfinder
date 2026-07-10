package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.BundleContentsComponentMixin;
import net.itemfinder.main.mixin.ContainerComponentMixin;
import net.itemfinder.main.mixin.ServerEntityManagerMixin;
import net.itemfinder.main.mixin.ServerWorldMixin;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.ChunkDataList;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static net.itemfinder.main.Controller.*;

public class SummaryBuilder {

    static final ConcurrentHashMap<ItemStackWrapper, SummaryResult> itemResults = new ConcurrentHashMap<>(10000);
    static final ConcurrentHashMap<String, SummaryResult> lootTableResults = new ConcurrentHashMap<>(1000);

    static SummaryResult emptyChests;
    static SummaryResult emptyChestsNoLootTable;

    @SuppressWarnings("SameReturnValue")
    public static int buildSummaryGlobal(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        if (searching) {
            getSourcePlayer(context).sendMessage(
                    Text.of("Search is already active (" + (System.nanoTime() - startTime) / 1000000000
                            + "s., requested by " + currentUser.getGameProfile().name() + ")"));
            return 1;
        }

        itemResults.clear();
        lootTableResults.clear();
        summaryCoordinates.clear();
        currentUser = getSourcePlayer(context);
        currentUser.sendMessage(Text.of("Saving chunk data..."));
        Objects.requireNonNull(currentUser.getEntityWorld().getServer()).save(true, true, false);

        searching = true;
        startTime = System.nanoTime();
        emptyChests = new SummaryResult();
        emptyChestsNoLootTable = new SummaryResult();

        scanExecutor.submit(() -> {
            ServerWorld world = currentUser.getEntityWorld();
            List<Long> chunkPositions = getChunkPositions(world);

            chunkCount = chunkPositions.size();
            currentUser.sendMessage(Text.of("Checking " + chunkCount + " chunks..."));
            AtomicInteger progress = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

            //Iterating through all generated chunks, extracting their block entity & entity data.
            for (Long position : chunkPositions) {
                if (!searching) {
                    sendResults();
                    break;
                }
                ChunkPos pos = new ChunkPos((int) (position >> 32), position.intValue());

                CompletableFuture<Void> future = new CompletableFuture<>();
                futures.add(future);

                CompletableFuture<Optional<NbtCompound>> chunkNBT = world.getChunkManager().chunkLoadingManager.getNbt(pos);
                @SuppressWarnings("unchecked") CompletableFuture<ChunkDataList<Entity>> entityNBT =
                        ((ServerEntityManagerMixin<Entity>) (((ServerWorldMixin) world).getEntityManager())).getDataAccess().readChunkData(pos);
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
                    if (!searching || compound.isEmpty()) {
                        future.complete(null);
                        return;
                    }

                    NbtCompound nbtData = compound.get();
                    try {
                        if (!searching) {
                            future.complete(null);
                            return;
                        }
                        nbtData.getList("block_entities").ifPresent(list ->
                                list.forEach(nbtElement -> checkBlockEntity((NbtCompound) nbtElement)));
                    }
                    catch (Throwable e) {
                        IFMod.LOGGER.error("Failed to process chunk {} with data of size {}.", pos, nbtData.getSize());
                        IFMod.LOGGER.error(e.getMessage());
                        future.complete(null);
                        throw e;
                    }

                    ChunkDataList<Entity> entities;
                    try {
                        entities = entityNBT.get();
                    }
                    catch (Exception e) {
                        future.complete(null);
                        return;
                    }
                    if (!searching || entities.isEmpty()) {
                        future.complete(null);
                        return;
                    }
                    try {
                        if (!searching) {
                            future.complete(null);
                            return;
                        }
                        entities.stream().forEach(SummaryBuilder::checkEntity);
                    }
                    catch (Throwable e) {
                        IFMod.LOGGER.error("Failed to process entity chunk {}.", pos);
                        future.complete(null);
                        throw e;
                    }
                    currentUser.sendMessage(Text.literal("Progress: " + progress.get() + "/" + chunkCount + " chunks")
                            .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)), true);
                    future.complete(null);
                });
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                if (searching) {
                    currentUser.sendMessage(Text.literal("Finished in " + (System.nanoTime() - startTime) / 1000000000 + "s.")
                            .setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
                    sendResults();
                    searching = false;
                }
            }
            catch (Throwable e) {
                searching = false;
                IFMod.LOGGER.error("Scan crashed!! Congratulations :)", e);
                throw new RuntimeException(e);
            }
        });
        return 1;
    }

    private static void submitStack(ItemStack stack, BlockPos pos) {
        int count = stack.getCount();
        stack.setCount(1);
        itemResults.compute(new ItemStackWrapper(stack), (stack1, result) -> {
            if (result == null) result = new SummaryResult();
            result.adder.add(count);
            result.coordinates.add(pos);
            return result;
        });
    }

    static final Set<String> lootableIDs = Set.of("minecraft:barrel", "minecraft:dispenser", "minecraft:dropper",
            "minecraft:hopper", "minecraft:shulker_box", "minecraft:trapped_chest");

    private static void checkBlockEntity(NbtCompound nbt) {
        blockCount.incrementAndGet();

        NbtList inventory = nbt.getListOrEmpty("Items");
        BlockPos pos = new BlockPos(nbt.getInt("x", 0), nbt.getInt("y", 0), nbt.getInt("z", 0));
        String id = nbt.getString("id", "");
        boolean isChest = id.equals("minecraft:chest");

        if (!inventory.isEmpty()) checkInventoryNBT(nbt.getListOrEmpty("Items"), pos);
        else if (isChest) {
            emptyChests.adder.increment();
            emptyChests.coordinates.add(pos);
        }

        //loot tables
        if (isChest || (!IFConfig.INSTANCE.onlyShowChestsLootTable && lootableIDs.contains(id))) {

            String lootTable = nbt.getString("LootTable", "");

            if (!lootTable.isEmpty()) {
                lootTableResults.compute(lootTable, (table, result) -> {
                    if (result == null) result = new SummaryResult();
                    result.adder.increment();
                    result.coordinates.add(pos);
                    return result;
                });
            }
            else if (inventory.isEmpty() && isChest) {
                emptyChestsNoLootTable.adder.increment();
                emptyChestsNoLootTable.coordinates.add(pos);
            }
        }
    }

    private static void checkEntity(Entity entity) {
        entityCount.incrementAndGet();
        List<ItemStack> inventory = ItemFinder.entityToInventory(entity);
        if (!inventory.isEmpty()) checkInventory(inventory, entity.getBlockPos());
    }

    public static void checkInventory(List<ItemStack> inventory, BlockPos pos) {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                checkNested(stack, pos);
                submitStack(stack, pos);
            }
        }
    }

    private static void checkInventoryNBT(NbtList inventory, BlockPos pos) {
        for (NbtElement item : inventory) {
            ItemStack stack = ((NbtCompound) item).decode(ItemStack.MAP_CODEC, currentUser.getEntityWorld().getRegistryManager().getOps(NbtOps.INSTANCE))
                    .orElse(ItemFinder.ERROR_STACK);

            if (!stack.isEmpty()) {
                checkNested(stack, pos);
                submitStack(stack, pos);
            }
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private static void checkNested(ItemStack stack, BlockPos pos) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        ComponentMap map = stack.getComponents();

        if (id.contains("bundle") && map.contains(DataComponentTypes.BUNDLE_CONTENTS))
            checkInventory(((BundleContentsComponentMixin) (Object) map.get(DataComponentTypes.BUNDLE_CONTENTS)).getStacks(), pos);
        else if (id.contains("shulker_box"))
            checkInventory(((ContainerComponentMixin) (Object) map.get(DataComponentTypes.CONTAINER)).getStacks(), pos);
    }

    private static void sendResults() {
        int coordinateSetID = 0;

        currentUser.sendMessage(Text.literal("/-----------------------------/").setStyle(delimeterStyle));

        addSummaryCoordinates(emptyChests.coordinates);
        currentUser.sendMessage(Text.literal("Empty chests: " + emptyChests.adder.longValue())
                .append(makeTeleportButton(coordinateSetID)));
        coordinateSetID++;
        addSummaryCoordinates(emptyChestsNoLootTable.coordinates);
        currentUser.sendMessage(Text.literal("Empty chests (no loot table): " + emptyChestsNoLootTable.adder.longValue())
                .append(makeTeleportButton(coordinateSetID)));
        coordinateSetID++;

        if (!itemResults.isEmpty()) {
            currentUser.sendMessage(Text.of("-------------------------------"));
            List<Pair<ItemStack, SummaryOutput>> itemResultList = SummaryBuilder.itemResults.entrySet().stream()
                    .map(entry -> new Pair<>(entry.getKey().stack, new SummaryOutput(entry.getValue())))
                    .sorted(SummaryBuilder::compareItemPairs)
                    .toList();
            currentUser.sendMessage(Text.literal("Items:").setStyle(Style.EMPTY.withBold(true)));

            int i = 0;
            for (Pair<ItemStack, SummaryOutput> result : itemResultList) {
                addSummaryCoordinates(result.getRight().coordinates);
                currentUser.sendMessage(makeItemMessage(++i, result.getLeft(), result.getRight().count, coordinateSetID));
                coordinateSetID++;
            }
        }
        if (!lootTableResults.isEmpty()) {
            currentUser.sendMessage(Text.of("-------------------------------"));
            List<Pair<String, SummaryOutput>> lootTableResultList = SummaryBuilder.lootTableResults.entrySet().stream()
                    .map(entry -> new Pair<>(entry.getKey(), new SummaryOutput(entry.getValue())))
                    .sorted(SummaryBuilder::compareLootTablePairs)
                    .toList();
            currentUser.sendMessage(Text.literal("Loot tables:").setStyle(Style.EMPTY.withBold(true)));

            int i = 0;
            for (Pair<String, SummaryOutput> result : lootTableResultList) {
                addSummaryCoordinates(result.getRight().coordinates);
                currentUser.sendMessage(makeLootTableMessage(++i, result.getLeft(), result.getRight().count, coordinateSetID));
                coordinateSetID++;
            }
        }
        currentUser.sendMessage(Text.literal("/-----------------------------/").setStyle(delimeterStyle));

        reset();
    }

    private static final Style delimeterStyle = Style.EMPTY
            .withColor(Formatting.AQUA)
            .withBold(true)
            .withItalic(false)
            .withUnderline(false)
            .withStrikethrough(false)
            .withObfuscated(false)
            .withUnderline(false);

    private static final Style blankStyle = Style.EMPTY
            .withColor(Formatting.WHITE)
            .withBold(false)
            .withItalic(false)
            .withUnderline(false)
            .withStrikethrough(false)
            .withObfuscated(false)
            .withUnderline(false)
            .withClickEvent(null)
            .withHoverEvent(null);

    //format: 1. <item stack name> | <count> ➡
    private static Text makeItemMessage(int i, ItemStack stack, long count, int coordinateSetID) {
        return Text.literal("  " + i + ". ")
                .append(stack.getName().copy().styled(style -> style.withHoverEvent(new HoverEvent.ShowItem(stack))))
                .append(Text.literal(" | ").setStyle(delimeterStyle))
                .append(Text.literal(Long.toString(count)).setStyle(blankStyle))
                .append(makeTeleportButton(coordinateSetID));
    }

    //format: 1. <loot table name> | <count> ➡
    private static Text makeLootTableMessage(int i, String lootTable, long count, int coordinateSetID) {
        return Text.literal("  " + i + ". ")
                .append(Text.of(lootTable))
                .append(Text.literal(" | ").setStyle(delimeterStyle))
                .append(Text.literal(Long.toString(count))).setStyle(blankStyle)
                .append(makeTeleportButton(coordinateSetID));
    }

    private static MutableText makeTeleportButton(int coordinateSetID) {
        return Text.literal(" ").append(Text.literal("➡").setStyle(Style.EMPTY
                        .withHoverEvent(new HoverEvent.ShowText(Text.of("Set teleport queue")))
                        .withClickEvent(new ClickEvent.RunCommand("/finditem set_queue " + coordinateSetID))
                        .withColor(Formatting.AQUA)
                        .withUnderline(true)));
    }

    private static int compareItemPairs(Pair<ItemStack, SummaryOutput> o1, Pair<ItemStack, SummaryOutput> o2) {
        switch (IFConfig.INSTANCE.summarySortMode) {
            case "Count" -> {
                return Long.compare(o2.getRight().count, o1.getRight().count);
            }
            case "Name" -> {
                return o1.getLeft().getName().getString().compareTo(o2.getLeft().getName().getString());
            }
            default -> {
                return 0;
            }
        }
    }

    private static int compareLootTablePairs(Pair<String, SummaryOutput> o1, Pair<String, SummaryOutput> o2) {
        switch (IFConfig.INSTANCE.summarySortMode) {
            case "Count" -> {
                return Long.compare(o2.getRight().count, o1.getRight().count);
            }
            case "Name" -> {
                return o1.getLeft().compareTo(o2.getLeft());
            }
            default -> {
                return 0;
            }
        }
    }

    //I can't believe this
    private record ItemStackWrapper(ItemStack stack) {

        @Override
        public boolean equals(Object wrapper) {
            assert wrapper instanceof ItemStackWrapper;
            return ItemStack.areItemsAndComponentsEqual(stack, ((ItemStackWrapper) wrapper).stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashCode(stack);
        }
    }

    private static class SummaryResult {

        final LongAdder adder;
        final Set<BlockPos> coordinates;

        SummaryResult() {
            adder = new LongAdder();
            coordinates = new HashSet<>();
        }
    }

    private static class SummaryOutput {

        final long count;
        final Set<BlockPos> coordinates;

        SummaryOutput(SummaryResult result) {
            this.count = result.adder.longValue();
            this.coordinates = result.coordinates;
        }
    }
}
