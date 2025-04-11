package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.*;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.*;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.ChunkDataList;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.itemfinder.main.Controller.*;

@Environment(EnvType.SERVER)
public class ItemFinder {

    static final Set<SearchResult> results = Collections.synchronizedSet(new HashSet<>());

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
        ((ThreadedAnvilChunkStorageMixin) world.getChunkManager().threadedAnvilChunkStorage)
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
                            + "s., requested by " + currentUser.getGameProfile().getName()));
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

                CompletableFuture<Optional<NbtCompound>> chunkNBT = world.getChunkManager().threadedAnvilChunkStorage.getNbt(pos);
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
     * Used to retrieve inventories of loaded entities, then sent to {@link #checkInventory(List, int, String, String, BlockPos)}.
     */
    public static void checkEntity(Entity entity, int type, String s) {
        entityCount.incrementAndGet();

        List<ItemStack> inventory = new ArrayList<>();
        if (entity instanceof ItemFrameEntity) inventory.add(((ItemFrameEntity) entity).getHeldItemStack());
        else if (entity instanceof ArmorStandEntity) entity.getItemsEquipped().forEach(inventory::add);
        else if (entity instanceof ItemEntity) inventory.add(((ItemEntity) entity).getStack());
        else if (entity instanceof VehicleInventory) inventory.addAll(((VehicleInventory) entity).getInventory());
        else if (entity instanceof DisplayEntity.ItemDisplayEntity
                && IFConfig.INSTANCE.scanItemDisplays) inventory.add(((ItemDisplayEntityMixin) entity).getItemStack());

        if (inventory.isEmpty()) return;

        checkInventory(inventory, type, s, ((EntityMixin) entity).getDefaultName().getString(), entity.getBlockPos());
    }

    /**
     * Used to retrieve inventories of loaded block entities, then sent to {@link #checkInventory(List, int, String, String, BlockPos)}.
     */
    public static void checkBlockEntity(String beName, BlockEntity be, int type, String s) {
        blockCount.incrementAndGet();

        List<ItemStack> inventory;
        if (be instanceof LootableContainerBlockEntity) inventory = ((LootableContainerBlockEntityMixin) be).getInvStackList();
        else if (be instanceof AbstractFurnaceBlockEntity) inventory = ((AbstractFurnaceBlockEntityMixin) be).getInventory();
        else if (be instanceof BrewingStandBlockEntity) inventory = ((BrewingStandBlockEntityMixin) be).getInventory();
        else if (be instanceof LecternBlockEntity) {
            inventory = new ArrayList<>();
            inventory.add(((LecternBlockEntity) be).getBook());
        }
        else if (be instanceof ChiseledBookshelfBlockEntity) inventory = ((ChiseledBookshelfBlockEntityMixin) be).getInventory();
        else return;

        checkInventory(inventory, type, s, beName, be.getPos());
    }

    /**
     * Adds a new entry to {@link #results} if given inventory contains an item that matches the search parameters (id, name, data).
     */
    public static void checkInventory(List<ItemStack> inventory, int type, String s, String name, BlockPos pos) {
        for (ItemStack stack : inventory) {
            NbtCompound nbt = stack.getNbt();
            String id = Registries.ITEM.getId(stack.getItem()).getPath();

            //If item has NBT data, see if it contains any items within it.
            if (nbt != null) checkNested(id, nbt.copy(), name, pos);

            switch (type) {
                case 0 -> {
                    if (!id.equals(s)) continue;
                }
                case 1 -> {
                    if (!stack.getName().getString().toLowerCase().contains(s.toLowerCase())) continue;
                }
                case 2 -> {
                    if (nbt == null || nbt.toString().toLowerCase().contains(s.toLowerCase())) continue;
                }
            }
            results.add(new SearchResult(name, pos, stack));
            return;
        }
    }

    /**
     * Used to get block name, position and inventory from NBT of unloaded block entities, then sent to {@link #checkInventoryNBT(NbtList, String, BlockPos)}.
     */
    public static void checkBlockEntityNBT(NbtCompound nbt) {
        blockCount.incrementAndGet();

        //minecraft:trapped_chest -> Trapped Chest
        String name = Arrays.stream(nbt.getString("id").replace("minecraft:", "").split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
        BlockPos pos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));

        checkInventoryNBT(nbt.getList("Items", 10), name, pos);
    }

    /**
     * Adds a new entry to {@link #results} if given inventory (in NBTList form) contains an item that matches the search parameters (id, name, data).
     */
    public static void checkInventoryNBT(NbtList nbt, String name, BlockPos pos) {
        for (NbtElement item : nbt) {
            NbtCompound compound = (NbtCompound) item;
            String id = compound.getString("id");

            //If item has NBT data, see if it contains any items within it.
            checkNested(id, compound.copy(), name, pos);
            ItemStack stack = ItemStack.fromNbt(compound);

            switch (searchType) {
                case 0 -> {
                    if (!id.substring(id.indexOf(':') + 1).equals(searchString)) continue;
                }
                case 1 -> {
                    if (!stack.getName().getString().toLowerCase().contains(searchString)) continue;
                }
                case 2 -> {
                    if (nbt.toString().toLowerCase().contains(searchString)) continue;
                }
            }
            results.add(new SearchResult(name, pos, stack));
            return;
        }
    }

    /**
     * Calls nested inventory check for found bundles & shulker boxes.
     */
    public static void checkNested(String id, NbtCompound nbt, String name, BlockPos pos) {
        if (nbt.contains("tag")) nbt = nbt.getCompound("tag");

        if (id.contains("bundle"))
            checkInventoryNBT(nbt.getList("Items", 10), name, pos);
        else if (id.contains("shulker_box"))
            checkInventoryNBT(nbt.getCompound("BlockEntityTag").getList("Items", 10), name, pos);
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
        Registries.ITEM.forEach(item -> {
            String name = Registries.ITEM.getId(item).getPath();
            if (name.contains(builder.getInput().toLowerCase().replace("/finditem id ", "").replace(" global", "")))
                builder.suggest(name);
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
