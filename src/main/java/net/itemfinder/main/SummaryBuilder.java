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
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Pair;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.ChunkDataList;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static net.itemfinder.main.Controller.*;

public class SummaryBuilder {

    static final ConcurrentHashMap<ItemStackWrapper, LongAdder> results = new ConcurrentHashMap<>(10000);

    @SuppressWarnings("SameReturnValue")
    public static int buildSummaryGlobal(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        if (searching) {
            getSourcePlayer(context).sendMessage(
                    Text.of("Search is already active (" + (System.nanoTime() - startTime) / 1000000000
                            + "s., requested by " + currentUser.getGameProfile().name() + ")"));
            return 1;
        }

        results.clear();
        currentUser = getSourcePlayer(context);
        currentUser.sendMessage(Text.of("Saving chunk data..."));
        Objects.requireNonNull(currentUser.getEntityWorld().getServer()).save(true, true, false);

        searching = true;
        startTime = System.nanoTime();

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
                    sendResults();
                    currentUser.sendMessage(Text.literal("Finished in " + (System.nanoTime() - startTime) / 1000000000 + "s.")
                            .setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
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

    private static void submitStack(ItemStack stack) {
        int count = stack.getCount();
        stack.setCount(1);
        results.compute(new ItemStackWrapper(stack), (stack1, adder) -> {
            if (adder == null) adder = new LongAdder();
            adder.add(count);
            return adder;
        });
    }

    private static void checkBlockEntity(NbtCompound nbt) {
        blockCount.incrementAndGet();
        checkInventoryNBT(nbt.getListOrEmpty("Items"));
    }

    private static void checkEntity(Entity entity) {
        entityCount.incrementAndGet();
        List<ItemStack> inventory = ItemFinder.entityToInventory(entity);
        if (!inventory.isEmpty()) checkInventory(inventory);
    }

    public static void checkInventory(List<ItemStack> inventory) {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                checkNested(stack);
                submitStack(stack);
            }
        }
    }

    private static void checkInventoryNBT(NbtList inventory) {
        for (NbtElement item : inventory) {
            ItemStack stack = ((NbtCompound) item).decode(ItemStack.MAP_CODEC, currentUser.getEntityWorld().getRegistryManager().getOps(NbtOps.INSTANCE))
                    .orElse(ItemFinder.ERROR_STACK);

            if (!stack.isEmpty()) {
                checkNested(stack);
                submitStack(stack);
            }
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private static void checkNested(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        ComponentMap map = stack.getComponents();

        if (id.contains("bundle") && map.contains(DataComponentTypes.BUNDLE_CONTENTS))
            checkInventory(((BundleContentsComponentMixin) (Object) map.get(DataComponentTypes.BUNDLE_CONTENTS)).getStacks());
        else if (id.contains("shulker_box"))
            checkInventory(((ContainerComponentMixin) (Object) map.get(DataComponentTypes.CONTAINER)).getStacks());
    }

    /**
     * Prints out search results with search stats.
     */
    private static void sendResults() {
        List<Pair<ItemStack, Long>> resultList = results.entrySet().stream()
                .map(entry -> new Pair<>(entry.getKey().stack, entry.getValue().longValue()))
                .sorted(SummaryBuilder::comparePairs)
                .toList();

        currentUser.sendMessage(Text.of("/-----------------------------/"));
        currentUser.sendMessage(Text.of("Items: " + blockCount));

        int i = 0;
        //format: 1. <item name> x<count>
        for (Pair<ItemStack, Long> result : resultList) currentUser.sendMessage(makeMessage(++i, result.getLeft(), result.getRight()));
        currentUser.sendMessage(Text.of("/-----------------------------/"));

        reset();
    }

    private static final Style delimeterStyle = Style.EMPTY
            .withBold(true)
            .withColor(Formatting.AQUA)
            .withItalic(false)
            .withUnderline(false)
            .withStrikethrough(false)
            .withObfuscated(false)
            .withUnderline(false);

    private static final Style countStyle = Style.EMPTY.withColor(Formatting.WHITE);

    /**
     * Used to make formatted lines for each search result entry.
     */
    private static Text makeMessage(int i, ItemStack stack, Long count) {
        MutableText text = Text.literal(i + ". ");
        text.append(stack.getName().copy().styled(style -> style.withHoverEvent(new HoverEvent.ShowItem(stack))));
        text.append(Text.literal(" | ").setStyle(delimeterStyle));
        text.append(Text.literal(count.toString()).setStyle(countStyle));
        return text;
    }

    private static int comparePairs(Pair<ItemStack, Long> o1, Pair<ItemStack, Long> o2) {
        switch (IFConfig.INSTANCE.summarySortMode) {
            case "Count" -> {
                return Long.compare(o2.getRight(), o1.getRight());
            }
            case "Name" -> {
                return o1.getLeft().getName().getString().compareTo(o2.getLeft().getName().getString());
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
}
