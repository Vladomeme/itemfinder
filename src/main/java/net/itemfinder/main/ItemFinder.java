package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChiseledBookshelfBlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.component.Component;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.ChunkDataList;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.itemfinder.main.Controller.*;

@Environment(EnvType.SERVER)
public class ItemFinder {

    static final Set<SearchResult> results = Collections.synchronizedSet(new HashSet<>());
    public static final ItemStack ERROR_STACK = new ItemStack(Items.STICK);

    /**
     * Runs a normal item search. Called via `/finditem id/name/data` without global modifier.
     */
    @SuppressWarnings("SameReturnValue")
    public static int search(int type, String s, CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = getSourcePlayer(context);
        if (searching.get()) {
            player.sendMessage(Text.of("Search already in progress..."));
            return 1;
        }
        currentUser = player;
        ServerWorld world = context.getSource().getWorld();

        //find matches in all loaded block entities with storage
        ((ServerChunkLoadingManagerMixin) world.getChunkManager().chunkLoadingManager)
                .entryIterator().forEach(chunkHolder -> {
                    WorldChunk chunk = chunkHolder.getWorldChunk();
                    if (chunk != null) chunk.getBlockEntities().values().forEach(be -> checkBlockEntity(
                            chunk.getBlockState(be.getPos()).getBlock().getName().getString(), be, type, s));
                });
        //find matches in all loaded storage-entities
        world.iterateEntities().forEach(entity -> checkEntity(entity, type, s));

        sendResults();
        return 1;
    }

    /**
     * Prepares a global item search. Called via `/finditem id/name/data` with global modifier.
     * Asks for confirmation according to current config.
     */
    @SuppressWarnings("SameReturnValue")
    public static int prepareGlobalSearch(int type, String s, CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        if (searching.get()) {
            getSourcePlayer(context).sendMessage(
                    Text.of("Search is already active (" + (System.nanoTime() - startTime) / 1000000000
                            + "s., requested by " + currentUser.getGameProfile().getName() + ")"));
            return 1;
        }

        //Set all scan parameters
        searchType = type;
        searchString = s.toLowerCase();
        currentUser = getSourcePlayer(context);
        itemSearchRequested = true;

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
        itemSearchRequested = false;
        searching.set(true);

        startTime = System.nanoTime();

        scanExecutor.submit(() -> {
            ServerWorld world = (ServerWorld) currentUser.getWorld();
            List<Long> chunkPositions = getChunkPositions(world);

            chunkCount = chunkPositions.size();
            currentUser.sendMessage(Text.of("Checking " + chunkCount + " chunks..."));
            AtomicInteger progress = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

            //Iterating through all generated chunks, extracting their block entity & entity data.
            for (Long position : chunkPositions) {
                if (!searching.get()) {
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

                    ChunkDataList<Entity> entities;
                    try {
                        entities = entityNBT.get();
                    }
                    catch (Exception e) {
                        future.complete(null);
                        return;
                    }
                    if (!searching.get() || entities.isEmpty()) {
                        future.complete(null);
                        return;
                    }
                    try {
                        if (!searching.get()) {
                            future.complete(null);
                            return;
                        }
                        entities.stream().forEach(entity -> checkEntity(entity, searchType, searchString));
                    }
                    catch (Throwable e) {
                        IFMod.LOGGER.error("Failed to deserialize entity chunk {}. Ignore if search finishes.", pos);
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
     * Retrieves and checks inventories of loaded entities via {@link #checkInventory(List, int, String)}.
     * Adds a search result if inventory matches.
     */
    public static void checkEntity(Entity entity, int type, String s) {
        entityCount.incrementAndGet();

        List<ItemStack> inventory = new ArrayList<>();
        if (entity instanceof ItemFrameEntity) inventory.add(((ItemFrameEntity) entity).getHeldItemStack());
        else if (entity instanceof ArmorStandEntity) ((ArmorStandEntity) entity).getEquippedItems().forEach(inventory::add);
        else if (entity instanceof ItemEntity) inventory.add(((ItemEntity) entity).getStack());
        else if (entity instanceof VehicleInventory) inventory.addAll(((VehicleInventory) entity).getInventory());
        else if (entity instanceof DisplayEntity.ItemDisplayEntity
                && IFConfig.INSTANCE.scanItemDisplays) inventory.add(((ItemDisplayEntityMixin) entity).getItemStack());

        if (inventory.isEmpty()) return;

        checkInventory(inventory, type, s).ifPresent(stack -> results.add(
                new SearchResult(((EntityMixin) entity).getDefaultName().getString(), entity.getBlockPos(), stack)));
    }

    /**
     * Retrieves and checks inventories of loaded block entities via {@link #checkInventory(List, int, String)}.
     * Adds a search result if inventory matches.
     */
    public static void checkBlockEntity(String name, BlockEntity be, int type, String s) {
        blockCount.incrementAndGet();

        List<ItemStack> inventory;
        if (be instanceof LockableContainerBlockEntity) inventory = ((LockableContainerBlockEntityMixin) be).getHeldStacks();
        else if (be instanceof LecternBlockEntity) {
            inventory = new ArrayList<>();
            inventory.add(((LecternBlockEntity) be).getBook());
        }
        else if (be instanceof ChiseledBookshelfBlockEntity) inventory = ((ChiseledBookshelfBlockEntityMixin) be).getInventory();
        else return;

        checkInventory(inventory, type, s).ifPresent(stack -> results.add(new SearchResult(name, be.getPos(), stack)));
    }

    /**
     * If given inventory contains an item stack that matches the search parameters (id/name/data), returns that item stack.
     */
    public static Optional<ItemStack> checkInventory(List<ItemStack> inventory, int type, String s) {
        for (ItemStack stack : inventory) {
            ComponentMap components = stack.getComponents();
            String id = Registries.ITEM.getId(stack.getItem()).getPath();

            //If item has NBT data, see if it contains any items.
            Optional<ItemStack> optionalStack = checkNested(id, components);
            if (optionalStack.isPresent()) return optionalStack;

            switch (type) {
                case 0 -> {
                    if (!id.equals(s)) continue;
                }
                case 1 -> {
                    if (!stack.getName().getString().toLowerCase().contains(s.toLowerCase())) continue;
                }
                case 2 -> {
                    if (components == ComponentMap.EMPTY || components == null) continue;
                    Optional<Component<?>> result = components.stream()
                            .filter(component -> String.valueOf(component.value()).toLowerCase().contains(s.toLowerCase()))
                            .findFirst();
                    if (result.isEmpty()) continue;
                }
            }
            return Optional.of(stack);
        }
        return Optional.empty();
    }

    /**
     * Checks block entity inventory via {@link #checkInventoryNBT(NbtList)}, gets block name and position, adds a search result if inventory matches.
     */
    public static void checkBlockEntityNBT(NbtCompound nbt) {
        blockCount.incrementAndGet();

        Optional<ItemStack> stack = checkInventoryNBT(nbt.getList("Items", 10));
        if (stack.isPresent()) {
            //minecraft:trapped_chest -> Trapped Chest
            String name = Arrays.stream(nbt.getString("id").replace("minecraft:", "").split("_"))
                    .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                    .collect(Collectors.joining(" "));
            BlockPos pos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));

            results.add(new SearchResult(name, pos, stack.get()));
        }
    }

    /**
     * If given inventory (in NBT form) contains an item stack that matches the search parameters (id/name/data), returns that item stack.
     */
    public static Optional<ItemStack> checkInventoryNBT(NbtList list) {
        for (NbtElement item : list) {
            NbtCompound nbt = (NbtCompound) item;
            String id = nbt.getString("id");

            //If item has NBT data, see if it contains any items within it.
            Optional<ItemStack> stack = checkNestedNBT(id, nbt.copy());
            if (stack.isPresent()) return stack;

            switch (searchType) {
                case 0 -> {
                    if (!id.substring(id.indexOf(':') + 1).equals(searchString)) continue;
                }
                case 1 -> {
                    if (nbt.getCompound("tag").getCompound("display").contains("Name")) {
                        if (!nbt.getCompound("tag").getCompound("display").getString("Name").toLowerCase()
                                .contains(searchString)) continue;
                    }
                    else if (!id.substring(id.indexOf(':') + 1).equals(searchString)) continue;
                }
                case 2 -> {
                    if (!nbt.toString().toLowerCase().contains(searchString.toLowerCase())) continue;
                }
            }
            return Optional.of(ItemStack.fromNbt(currentUser.getRegistryManager(), nbt).orElse(ERROR_STACK));
        }
        return Optional.empty();
    }

    /**
     * Calls nested inventory check for found bundles & shulker boxes (in Data Component form).
     */
    @SuppressWarnings("DataFlowIssue")
    private static Optional<ItemStack> checkNested(String id, ComponentMap map) {
        if (id.contains("bundle") && map.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            return checkInventory(((BundleContentsComponentMixin) (Object) map.get(DataComponentTypes.BUNDLE_CONTENTS)).getStacks(),
                    searchType, searchString);
        }
        else if (id.contains("shulker_box")) {
            return checkInventory(((ContainerComponentMixin) (Object) map.get(DataComponentTypes.CONTAINER)).getStacks(),
                    searchType, searchString);
        }
        return Optional.empty();
    }

    /**
     * Calls nested inventory check for found bundles & shulker boxes (in NBT form).
     */
    public static Optional<ItemStack> checkNestedNBT(String id, NbtCompound nbt) {
        if (nbt.contains("tag")) nbt = nbt.getCompound("tag");

        if (id.contains("bundle")) return checkInventoryNBT(nbt.getList("Items", 10));
        else if (id.contains("shulker_box")) return checkInventoryNBT(nbt.getCompound("BlockEntityTag").getList("Items", 10));
        return Optional.empty();
    }

    /**
     * Prints out search results with search stats & teleportation commands.
     */
    public static void sendResults() {
        currentUser.sendMessage(Text.of("/-----------------------------/"));
        currentUser.sendMessage(Text.of("Blocks/entities searched: " + blockCount + "/" + entityCount));
        currentUser.sendMessage(Text.of("Matching results: " + results.size() +
                (results.isEmpty() ? " :(" : "")));

        //format: 1. <block/entity name> [x, y, z]
        int i = 0;
        for (SearchResult result : results) currentUser.sendMessage(makeMessage(++i, result.name(), result.pos(), result.stack()));
        currentUser.sendMessage(Text.of("/-----------------------------/"));

        reset();
    }

    /**
     * Used to make formatted lines for each search result entry.
     */
    public static Text makeMessage(int i, String name, BlockPos pos, ItemStack stack) {
        return Text.literal((i) + ". ")
                .append(Text.literal(name)
                        .setStyle(Style.EMPTY
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,
                                        new HoverEvent.ItemStackContent(stack)))))
                .append(Text.literal(" "))
                .append(Text.literal("[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "]")
                        .setStyle(Style.EMPTY
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.of("Click to teleport")))
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                                .withColor(Formatting.AQUA)
                                .withUnderline(true)));
    }

    /**
     * Returns item IDs for `/finditem id` autocompletion.
     */
    @SuppressWarnings("unused")
    public static CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String input = builder.getInput().toLowerCase().replace("/finditem id ", "").replace(" global", "");
        Registries.ITEM.forEach(item -> {
            String name = Registries.ITEM.getId(item).getPath();
            if (name.contains(input)) builder.suggest(name);
        });
        return builder.buildFuture();
    }

    public record SearchResult(String name, BlockPos pos, ItemStack stack) implements Comparable<SearchResult> {

        @Override
        public int compareTo(@NotNull ItemFinder.SearchResult o) {
            if (pos().equals(o.pos())) return 0;
            if (Math.abs(pos.getX()) + Math.abs(pos().getY()) + Math.abs(pos().getZ())
                    > Math.abs(o.pos.getX()) + Math.abs(o.pos().getY()) + Math.abs(o.pos().getZ())) return 1;
            return -1;
        }
    }
}
