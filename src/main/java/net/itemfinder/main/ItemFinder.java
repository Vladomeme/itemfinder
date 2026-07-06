package net.itemfinder.main;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.itemfinder.main.config.IFConfig;
import net.itemfinder.main.mixin.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.component.Component;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.inventory.ListInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
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
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.ChunkDataList;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static net.itemfinder.main.Controller.*;

public class ItemFinder {

    static final ConcurrentLinkedQueue<SearchResult> results = new ConcurrentLinkedQueue<>();
    public static final ItemStack ERROR_STACK = new ItemStack(Items.STICK);

    /**
     * Runs a normal item search. Called via `/finditem id/name/data` without global modifier.
     */
    @SuppressWarnings("SameReturnValue")
    public static int search(int type, String s, CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = getSourcePlayer(context);
        if (searching) {
            player.sendMessage(Text.of("Search already in progress..."));
            return 1;
        }
        currentUser = player;
        ServerWorld world = context.getSource().getWorld();

        //find matches in all loaded block entities with storage
        ((ServerChunkLoadingManagerMixin) world.getChunkManager().chunkLoadingManager)
                .chunkHolders().values().forEach(chunkHolder -> {
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
        if (searching) {
            getSourcePlayer(context).sendMessage(
                    Text.of("Search is already active (" + (System.nanoTime() - startTime) / 1000000000
                            + "s., requested by " + currentUser.getGameProfile().name() + ")"));
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
                    .withClickEvent(new ClickEvent.RunCommand("/finditem confirm"))), false);
        }
        return 1;
    }

    /**
     * Runs a global item search by retrieving positions of all generated chunks and reading their NBT data.
     */
    @SuppressWarnings("SameReturnValue")
    public static void globalSearch() {
        currentUser.sendMessage(Text.of("Saving chunk data..."));
        Objects.requireNonNull(currentUser.getEntityWorld().getServer()).save(true, true, false);

        itemSearchRequested = false;
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
                                list.forEach(nbtElement -> checkBlockEntityNBT((NbtCompound) nbtElement)));
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
                        entities.stream().forEach(entity -> checkEntity(entity, searchType, searchString));
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

                sendResults();
                currentUser.sendMessage(Text.literal("Finished in " + (System.nanoTime() - startTime) / 1000000000 + "s.")
                        .setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
                searching = false;
            }
            catch (Throwable e) {
                searching = false;
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

        List<ItemStack> inventory = entityToInventory(entity);

        if (inventory.isEmpty()) return;

        checkInventory(inventory, type, s).ifPresent(stack -> results.add(
                new SearchResult(((EntityMixin) entity).getDefaultName().getString(), entity.getBlockPos(), stack)));
    }

    public static List<ItemStack> entityToInventory(Entity entity) {
        List<ItemStack> inventory = new ArrayList<>();

        if (entity instanceof ItemFrameEntity) inventory.add(((ItemFrameEntity) entity).getHeldItemStack());
        else if (entity instanceof ArmorStandEntity) getEquipment(entity, inventory);
        else if (entity instanceof ItemEntity) inventory.add(((ItemEntity) entity).getStack());
        else if (entity instanceof VehicleInventory) inventory.addAll(((VehicleInventory) entity).getInventory());
        else if (entity instanceof DisplayEntity.ItemDisplayEntity && IFConfig.INSTANCE.scanItemDisplays)
            inventory.add(((ItemDisplayEntityMixin) entity).getItemStack());
        else if (entity instanceof MerchantEntity && IFConfig.INSTANCE.scanTrades)
            getTrades((MerchantEntity) entity, inventory);

        return inventory;
    }

    public static void getEquipment(Entity entity, List<ItemStack> inventory) {
        ((EntityEquipmentMixin) ((LivingEntityMixin) entity).equipment()).map().values().forEach(stack -> {
            if (stack != null && !stack.getItem().equals(Items.AIR)) inventory.add(stack);
        });
    }

    public static void getTrades(MerchantEntity entity, List<ItemStack> inventory) {
        TradeOfferList offers = ((MerchantEntityMixin) entity).getOffers();
        if (offers == null) return;

        for (TradeOffer offer : offers) {
            ItemStack stack1 = offer.getFirstBuyItem().itemStack();
            ItemStack stack2 = offer.getSecondBuyItem().isPresent() ? offer.getSecondBuyItem().get().itemStack() : null;
            ItemStack stack3 = offer.getSellItem();

            inventory.add(stack1);
            if (stack2 != null && !stack2.getItem().equals(Items.AIR)) inventory.add(stack2);
            inventory.add(stack3);
        }
    }

    /**
     * Retrieves and checks inventories of loaded block entities via {@link #checkInventory(List, int, String)}.
     * Adds a search result if inventory matches.
     */
    public static void checkBlockEntity(String name, BlockEntity be, int type, String s) {
        blockCount.incrementAndGet();

        List<ItemStack> inventory;
        switch (be) {
            case LockableContainerBlockEntity lcbe -> inventory = ((LockableContainerBlockEntityMixin) lcbe).getHeldStacks();
            case LecternBlockEntity lecternBlockEntity -> {
                inventory = new ArrayList<>();
                inventory.add(lecternBlockEntity.getBook());
            }
            case ListInventory listInventory -> inventory = listInventory.getHeldStacks();
            case null, default -> {
                return;
            }
        }

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
                    if (id.equals(s)) return Optional.of(stack);
                }
                case 1 -> {
                    if (stack.getName().getString().toLowerCase().contains(s)) return Optional.of(stack);
                }
                case 2 -> {
                    if (components == ComponentMap.EMPTY || components == null) continue;
                    if (checkComponents(components, s)) return Optional.of(stack);
                }
            }
        }
        return Optional.empty();
    }

    public static boolean checkComponents(ComponentMap components, String s) {
        for (Component<?> untypedComponent : components) {
            //because empty components show up on every item, fuck you mojang
            if (IFConfig.INSTANCE.ignoreDefaultComponents) {
                switch (untypedComponent.value()) {
                    case LoreComponent component -> {
                        if (component.lines().isEmpty() && component.styledLines().isEmpty()) continue;
                    }
                    case AttributeModifiersComponent component -> {
                        if (component.modifiers().isEmpty()) continue;
                    }
                    case ItemEnchantmentsComponent component -> {
                        if (((ItemEnchantmentsComponentMixin) component).enchantments().isEmpty()) continue;
                    }
                    default -> {}
                }
            }
            if (String.valueOf(untypedComponent.type()).toLowerCase().contains(s)
                    || String.valueOf(untypedComponent.value()).toLowerCase().contains(s)) return true;
        }
        return false;
    }

    /**
     * Checks block entity inventory via {@link #checkInventoryNBT(NbtList, boolean)}, gets block name and position, adds a search result if inventory matches.
     */
    public static void checkBlockEntityNBT(NbtCompound nbt) {
        blockCount.incrementAndGet();

        Optional<ItemStack> stack = checkInventoryNBT(nbt.getListOrEmpty("Items"), false);
        if (stack.isPresent()) {
            String name = idToName(nbt.getString("id", "unknown"));
            BlockPos pos = new BlockPos(nbt.getInt("x", 0), nbt.getInt("y", 0), nbt.getInt("z", 0));

            results.add(new SearchResult(name, pos, stack.get()));
        }
    }

    /**
     * If given inventory (in NBT form) contains an item stack that matches the search parameters (id/name/data), returns that item stack.
     */
    public static Optional<ItemStack> checkInventoryNBT(NbtList inventory, boolean isShulker) {
        for (NbtElement item : inventory) {
            NbtCompound nbt = isShulker ? ((NbtCompound) item).getCompoundOrEmpty("item") : (NbtCompound) item;
            String id = nbt.getString("id", "");

            //If item has NBT data, see if it contains any items within it.
            Optional<ItemStack> stack = checkNestedNBT(id, nbt.copy());
            if (stack.isPresent()) return stack;

            switch (searchType) {
                case 0 -> {
                    if (!id.substring(id.indexOf(':') + 1).equals(searchString)) continue;
                }
                case 1 -> {
                    if (!checkName(nbt, id)) continue;
                }
                case 2 -> {
                    if (!nbt.toString().toLowerCase().contains(searchString)) continue;
                }
            }
            return Optional.of(nbt.decode(ItemStack.MAP_CODEC, currentUser.getEntityWorld().getRegistryManager().getOps(NbtOps.INSTANCE)).orElse(ERROR_STACK));
        }
        return Optional.empty();
    }

    private static boolean checkName(NbtCompound nbt, String id) {
        NbtElement nameElement = nbt.getCompoundOrEmpty("components").get("minecraft:custom_name");
        if (nameElement == null) nameElement = nbt.getCompoundOrEmpty("components").get("minecraft:item_name");

        if (nameElement != null) {
            switch (nameElement.getType()) {
                case 8 -> { //straight string
                    return ((NbtString) nameElement).value().toLowerCase().contains(searchString);
                }
                case 9 -> { //NbtCompound list
                    StringBuilder builder = new StringBuilder();
                    NbtList list = (NbtList) nameElement;
                    for (NbtElement element : list) {
                        if (element.getType() == 8) builder.append(((NbtString) element).value());
                        else builder.append(((NbtCompound) element).getString("text", ""));
                    }
                    if (builder.toString().toLowerCase().contains(searchString)) return true;
                    builder.setLength(0);
                    for (NbtElement element : list) {
                        if (element.getType() == 8) builder.append(((NbtString) element).value());
                        else builder.append(((NbtCompound) element).getString("fallback", ""));
                    }
                    if (builder.toString().toLowerCase().contains(searchString)) return true;
                    builder.setLength(0);
                    for (NbtElement element : list) {
                        if (element.getType() == 8) builder.append(((NbtString) element).value());
                        else builder.append(((NbtCompound) element).getString("translate", ""));
                    }
                    return (builder.toString().toLowerCase().contains(searchString));
                }
                case 10 -> { // NbtCompound
                    NbtCompound nameCompound = (NbtCompound) nameElement;
                    return nameCompound.getString("text", "").toLowerCase().contains(searchString)
                            || nameCompound.getString("fallback", "").toLowerCase().contains(searchString)
                            || nameCompound.getString("translate", "").toLowerCase().contains(searchString);
                }
            }
            return false;
        }
        else return id.substring(id.indexOf(':') + 1).contains(searchString);
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
        if (id.contains("bundle"))
            return checkInventoryNBT(nbt.getCompoundOrEmpty("components").getListOrEmpty("minecraft:bundle_contents"), false);
        else if (id.contains("shulker_box"))
            return checkInventoryNBT(nbt.getCompoundOrEmpty("components").getListOrEmpty("minecraft:container"), true);
        return Optional.empty();
    }

    /**
     * Prints out search results with search stats & teleportation commands.
     */
    public static void sendResults() {
        List<SearchResult> resultList = new ArrayList<>(results);
        currentUser.sendMessage(Text.of("/-----------------------------/"));
        currentUser.sendMessage(Text.of("Blocks/entities searched: " + blockCount + "/" + entityCount));
        currentUser.sendMessage(Text.of("Matching results: " + resultList.size() +
                (resultList.isEmpty() ? " :(" : "")));

        int i = 0;
        resultList.sort(AbstractSearchResult::compare);
        //format: 1. <block/entity name> [x, y, z]
        for (SearchResult result : resultList) currentUser.sendMessage(makeMessage(++i, result.name, result.pos, result.stack));
        currentUser.sendMessage(Text.of("/-----------------------------/"));

        setPlayerCoordinates(resultList);
        reset();
    }

    /**
     * Used to make formatted lines for each search result entry.
     */
    public static Text makeMessage(int i, String name, BlockPos pos, ItemStack stack) {
        return Text.literal((i) + ". ")
                .append(Text.literal(name)
                        .setStyle(Style.EMPTY
                                .withHoverEvent(new HoverEvent.ShowItem(stack))))
                .append(Text.literal(" "))
                .append(Text.literal("[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "]")
                        .setStyle(Style.EMPTY
                                .withHoverEvent(new HoverEvent.ShowText(Text.of("Click to teleport")))
                                .withClickEvent(new ClickEvent.RunCommand("/tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                                .withColor(Formatting.AQUA)
                                .withUnderline(true)));
    }

    //minecraft:trapped_chest -> Trapped Chest
    public static String idToName(String s) {
        int index = s.indexOf(':');
        if (index != -1) s = s.substring(index + 1);

        char[] chars = s.toCharArray();
        chars[0] -= 32;
        for (int i = 1; i < chars.length; i++) {
            if (chars[i] == '_') {
                chars[i] = ' ';
                chars[i + 1] -= 32;
                i++;
            }
        }
        return String.copyValueOf(chars);
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

    public static class SearchResult extends AbstractSearchResult {

        final ItemStack stack;

        SearchResult(String name, BlockPos pos, ItemStack stack) {
            this.name = name;
            this.pos = pos;
            this.stack = stack;
        }
    }
}
