package net.itemfinder.main;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.*;
import net.minecraft.block.entity.*;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandSource;
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
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.*;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.ChunkDataList;
import net.minecraft.world.storage.RegionFile;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;

import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.*;
//Added config, check it out (via cloth config).
//Now works with chest boats and (optionally) item displays
//Added nested search for bundles and shulker boxes
public class ItemFinder implements ModInitializer, SuggestionProvider<CommandSource> {

    public static final Logger LOGGER = LoggerFactory.getLogger("itemfinder");

    private int threadCount = 0;
    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("Item-Finder-Scan-Worker-" + threadCount++);
        thread.setDaemon(true);
        return thread;
    });

    boolean globalSearchRequested = false;
    final AtomicBoolean searching = new AtomicBoolean(false);
    int chunkCount;
    int searchType;
    String searchString = "";
    ServerPlayerEntity player;

    final AtomicInteger blockCount = new AtomicInteger(0);
    final AtomicInteger entityCount = new AtomicInteger(0);

    final Map<BlockPos, String> blockResults = Collections.synchronizedMap(new HashMap<>());
    final Map<BlockPos, String> entityResults = Collections.synchronizedMap(new HashMap<>());

    KeyBinding teleportKey;
    final List<BlockPos> coordinates = new ArrayList<>();
    int currentPosition = 1;

    @Override
    public void onInitialize() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {

            @Override
            public Identifier getFabricId() {
                return new Identifier("itemfinder", "assets");
            }

            @Override
            public void reload(ResourceManager manager) {
                addCommand();
            }
        });

        teleportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Teleport to next result",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "Item Finder"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (teleportKey.wasPressed()) teleportToNext();
        });

        LOGGER.info("Item Finder loaded!");
    }

    public void addCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("finditem")
                        .then(literal("id")
                                .then(argument("id", StringArgumentType.string())
                                .suggests(this::getSuggestions)
                                .executes(context -> search(0, StringArgumentType.getString(context, "id"), context))
                                        .then(literal("global")
                                        .executes(context -> prepareGlobalSearch(0, StringArgumentType.getString(context, "id"), context))
                        )))
                        .then(literal("name")
                                .then(argument("name", StringArgumentType.string())
                                .executes(context -> search(1, StringArgumentType.getString(context, "name"), context))
                                        .then(literal("global")
                                        .executes(context -> prepareGlobalSearch(1, StringArgumentType.getString(context, "name"), context))
                        )))
                        .then(literal("data")
                                .then(argument("data", StringArgumentType.string())
                                .executes(context -> search(2, StringArgumentType.getString(context, "data"), context))
                                        .then(literal("global")
                                        .executes(context -> prepareGlobalSearch(2, StringArgumentType.getString(context, "data"), context))
                        )))
                        .then(literal("stop")
                                .executes(this::stop))
                        .then(literal("confirm")
                                .executes(this::globalSearch))));
    }

    @SuppressWarnings("SameReturnValue")
    private int search(int type, String s, CommandContext<ServerCommandSource> context) {
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
                            chunk.getBlockState(be.getPos()).getBlock().getName().getString(), be, type, s));
                });
        //find matches in all loaded item frames, armor stands and dropped items
        world.iterateEntities().forEach(entity -> checkEntity(entity, type, s));

        //send results to the player who executed the command
        sendResults(Objects.requireNonNull(context.getSource().getPlayer()));
        return 1;
    }

    @SuppressWarnings("SameReturnValue")
    private int prepareGlobalSearch(int type, String s, CommandContext<ServerCommandSource> context) {
        if (searching.get()) {
            Objects.requireNonNull(context.getSource().getPlayer()).sendMessage(Text.of("Search already in progress..."));
            return 1;
        }

        //Set all scan parameters
        this.searchType = type;
        this.searchString = s;
        this.player = Objects.requireNonNull(context.getSource().getPlayer());
        globalSearchRequested = true;

        if (IFConfig.INSTANCE.autoConfirm) globalSearch(context);
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
    private int globalSearch(CommandContext<ServerCommandSource> context) {
        if (!globalSearchRequested) {
            Objects.requireNonNull(context.getSource().getPlayer()).sendMessage(Text.of("nothing to confirm..."));
            return 1;
        }
        globalSearchRequested = false;
        searching.set(true);

        long start = System.nanoTime();

        scanExecutor.submit(() -> {
            ServerWorld world = context.getSource().getWorld();
            List<Long> chunkPositions = getChunkPositions(world);

            chunkCount = chunkPositions.size();
            player.sendMessage(Text.of("Checking " + chunkCount + " chunks..."));
            AtomicInteger progress = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

            ((ThreadedAnvilChunkStorageMixin) world.getChunkManager().threadedAnvilChunkStorage)
                    .entryIterator().forEach(chunkHolder -> {
                        WorldChunk chunk = chunkHolder.getWorldChunk();
                        if (chunk == null || !chunkHolder.isAccessible()) return;

                        chunk.getBlockEntities().values().forEach(be -> checkBlockEntity(chunk.getBlockState(be.getPos()).getBlock().getName().getString(),
                                be, searchType, searchString));
                        chunkPositions.remove(chunk.getPos().toLong());
                        progress.incrementAndGet();
                    });
            world.iterateEntities().forEach(entity -> checkEntity(entity, searchType, searchString));

            for (Long position : chunkPositions) {
                if (!searching.get()) {
                    sendResults(player);
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
                        LOGGER.error("Failed to deserialize chunk {} with data of size {}", pos, nbtData.getSize());
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
                        LOGGER.error("Failed to deserialize entity chunk {}", pos);
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
                LOGGER.error("Scan crashed!! Congratulations", e);
                throw new RuntimeException(e);
            }
        });
        return 1;
    }

    private List<Long> getChunkPositions(ServerWorld world) {
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

    private void checkEntity(Entity entity, int type, String s) {
        entityCount.incrementAndGet();

        List<ItemStack> inventory = new ArrayList<>();
        if (entity instanceof ItemFrameEntity) inventory.add(((ItemFrameEntity) entity).getHeldItemStack());
        else if (entity instanceof ArmorStandEntity) entity.getItemsEquipped().forEach(inventory::add);
        else if (entity instanceof ItemEntity) inventory.add(((ItemEntity) entity).getStack());
        else if (entity instanceof VehicleInventory) inventory.addAll(((VehicleInventory) entity).getInventory());
        else if (entity instanceof DisplayEntity.ItemDisplayEntity
                && IFConfig.INSTANCE.scanItemDisplays) inventory.add(((ItemDisplayEntityMixin) entity).getItemStack());

        if (inventory.isEmpty()) return;

        if (checkInventory(inventory, type, s)) entityResults.put(entity.getBlockPos(), ((EntityMixin) entity).getDefaultName().getString());
    }

    private void checkBlockEntity(String beName, BlockEntity be, int type, String s) {
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

        if (checkInventory(inventory, type, s)) blockResults.put(be.getPos(), beName);
    }

    private boolean checkInventory(List<ItemStack> inventory, int type, String s) {
        for (ItemStack stack : inventory) {
            NbtCompound nbt = stack.getNbt();
            String id = Registries.ITEM.getId(stack.getItem()).getPath();

            if (nbt != null && checkNested(id, nbt.copy())) return true;

            switch (type) {
                case 0 -> {
                    if (id.equals(s)) return true;
                }
                case 1 -> {
                    if (stack.getName().getString().toLowerCase().contains(s.toLowerCase())) return true;
                }
                case 2 -> {
                    if (nbt != null && nbt.toString().toLowerCase().contains(s.toLowerCase())) return true;
                }
            }
        }
        return false;
    }

    private void checkBlockEntityNBT(NbtCompound nbt) {
        blockCount.incrementAndGet();

        String name = Arrays.stream(nbt.getString("id").replace("minecraft:", "").split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
        BlockPos pos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));

        if (checkInventoryNBT(nbt.getList("Items", 10))) blockResults.put(pos, name);
    }

    private boolean checkInventoryNBT(NbtList nbt) {
        for (NbtElement item : nbt) {
            NbtCompound compound = (NbtCompound) item;
            String id = compound.getString("id");

            if (checkNested(id, compound.copy())) return true;

            switch (searchType) {
                case 0 -> {
                    if (compound.getString("id").replace("minecraft:", "").equals(searchString)) return true;
                }
                case 1 -> {
                    if (compound.getCompound("tag").getCompound("display").getString("Name").toLowerCase()
                            .contains(searchString.toLowerCase())) return true;
                }
                case 2 -> {
                    if (compound.toString().toLowerCase().contains(searchString.toLowerCase())) return true;
                }
            }
        }
        return false;
    }

    private boolean checkNested(String id, NbtCompound nbt) {
        if (nbt.contains("tag")) nbt = nbt.getCompound("tag");
        return ((id.contains("bundle") && checkInventoryNBT(nbt.getList("Items", 10)))
                || (id.contains("shulker_box") && checkInventoryNBT(nbt.getCompound("BlockEntityTag").getList("Items", 10))));
    }

    private void sendResults(ServerPlayerEntity player) {
        player.sendMessage(Text.of("/-----------------------------/"));
        //send results with teleportation commands
        player.sendMessage(Text.of("Blocks/entities searched: " + blockCount + "/" + entityCount));
        player.sendMessage(Text.of("Matching results: " + blockResults.size() + "/" + entityResults.size() +
                (blockResults.isEmpty() && entityResults.isEmpty() ? " :(" : "")));

        //format: 1. <block/entity name> [x, y, z]
        int i = 0;
        for (BlockPos pos : blockResults.keySet()) player.sendMessage(makeMessage(++i, pos, blockResults.get(pos)));
        for (BlockPos pos : entityResults.keySet()) player.sendMessage(makeMessage(++i, pos, entityResults.get(pos)));
        player.sendMessage(Text.of("/-----------------------------/"));

        reset();
    }

    private Text makeMessage(int i, BlockPos pos, String name) {
        return Text.literal((i) + ". " + name + " ")
                .append(Text.literal("[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "]")
                        .setStyle(Style.EMPTY
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.of("Click to teleport")))
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                                .withColor(Formatting.AQUA)
                                .withUnderline(true)));
    }

    private void teleportToNext() {
        //noinspection StatementWithEmptyBody
        while (teleportKey.wasPressed());
        if (player == null) return;

        if (coordinates.isEmpty() || currentPosition > coordinates.size()) {
            player.sendMessage(Text.of("No search results in teleport queue!"));
            return;
        }
        BlockPos pos = coordinates.get(currentPosition - 1);
        player.teleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.sendMessage(Text.literal("Teleporting to result " + currentPosition + "/" + coordinates.size())
                .setStyle(Style.EMPTY.withColor(Formatting.YELLOW)), true);
        currentPosition++;
    }

    public CompletableFuture<Suggestions> getSuggestions(CommandContext context, SuggestionsBuilder builder) {
        Registries.ITEM.forEach(item -> builder.suggest(Registries.ITEM.getId(item).getPath()));
        return builder.buildFuture();
    }

    @SuppressWarnings("SameReturnValue")
    private int stop(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = Objects.requireNonNull(context.getSource().getPlayer());
        player.sendMessage(Text.of(searching.get() ? "Search interrupted." : "why... search wasn't running..."));

        reset();
        return 1;
    }

    private void reset() {
        searching.set(false);
        blockCount.set(0);
        entityCount.set(0);
        coordinates.clear();
        coordinates.addAll(blockResults.keySet());
        coordinates.addAll(entityResults.keySet());
        currentPosition = 1;
        blockResults.clear();
        entityResults.clear();
    }
}
