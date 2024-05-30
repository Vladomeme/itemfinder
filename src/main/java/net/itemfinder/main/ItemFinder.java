package net.itemfinder.main;

import com.google.common.collect.Maps;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.itemfinder.main.mixin.*;
import net.minecraft.block.entity.*;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.*;

public class ItemFinder implements ModInitializer, SuggestionProvider<CommandSource> {

    public static final Logger LOGGER = LoggerFactory.getLogger("itemfinder");

    int blockCount = 0;
    int entityCount = 0;

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
        LOGGER.info("Item Finder loaded!");
    }

    public void addCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("finditem")
                        .then(literal("id")
                                .then(argument("id", StringArgumentType.string())
                                .suggests(this::getSuggestions)
                                .executes(context -> search(0, StringArgumentType.getString(context, "id"), context))
                        ))
                        .then(literal("name")
                                .then(argument("name", StringArgumentType.string())
                                .executes(context -> search(1, StringArgumentType.getString(context, "name"), context))
                        ))
                        .then(literal("data")
                                .then(argument("data", StringArgumentType.string())
                                        .executes(context -> search(2, StringArgumentType.getString(context, "data"), context)))
                        )));
    }

    private int search(int type, String s, CommandContext<ServerCommandSource> context) {
        ServerWorld world = context.getSource().getWorld();

        //find matches in all loaded block entities with storage
        HashMap<BlockPos, String> blockResults = Maps.newLinkedHashMap();
        ((ThreadedAnvilChunkStorageMixin) world.getChunkManager().threadedAnvilChunkStorage)
                .entryIterator().forEach(chunkHolder -> blockResults.putAll(scanChunk(chunkHolder, type, s)));

        //find matches in all loaded item frames, armor stands and dropped items
        HashMap<BlockPos, String> entityResults = Maps.newLinkedHashMap();
        entityResults.putAll(checkEntities(world, type, s));

        //get player who executed the command
        ServerPlayerEntity player = Objects.requireNonNull(context.getSource().getPlayer());

        //send results with teleportation commands
        player.sendMessage(Text.of("Blocks/entities searched: " + blockCount + "/" + entityCount));
        player.sendMessage(Text.of("Matching results: " + blockResults.size() + "/" + entityResults.size() +
                (blockResults.isEmpty() && entityResults.isEmpty() ? " :(" : "")));

        //format: 1. <block/entity name> [x, y, z]
        int i = 0;
        for (BlockPos pos : blockResults.keySet()) player.sendMessage(makeMessage(++i, pos, blockResults.get(pos)));
        for (BlockPos pos : entityResults.keySet()) player.sendMessage(makeMessage(++i, pos, entityResults.get(pos)));

        blockCount = 0;
        entityCount = 0;
        return 1;
    }

    private HashMap<BlockPos, String> scanChunk(ChunkHolder chunkHolder, int type, String s) {
        HashMap<BlockPos, String> results = Maps.newLinkedHashMap();
        if (chunkHolder.getWorldChunk() == null) return results;

        chunkHolder.getWorldChunk().getBlockEntities().values().forEach(be -> {

            DefaultedList<ItemStack> inventory;
            if (be instanceof LootableContainerBlockEntity) inventory = ((LootableContainerBlockEntityMixin) be).getInvStackList();
            else if (be instanceof AbstractFurnaceBlockEntity) inventory = ((AbstractFurnaceBlockEntityMixin) be).getInventory();
            else if (be instanceof BrewingStandBlockEntity) inventory = ((BrewingStandBlockEntityMixin) be).getInventory();
            else return;
            /*
            DefaultedList<ItemStack> inventory = switch (be) {
                case LootableContainerBlockEntity ignored -> ((LootableContainerBlockEntityMixin) be).getInvStackList();
                case AbstractFurnaceBlockEntity ignored -> ((AbstractFurnaceBlockEntityMixin) be).getInventory();
                case BrewingStandBlockEntity ignored -> ((BrewingStandBlockEntityMixin) be).getInventory();
                default -> null;
            };
            if (inventory == null) return;
             */
            if (checkInventory(inventory, type, s)) results.put(be.getPos(),
                    chunkHolder.getWorldChunk().getBlockState(be.getPos()).getBlock().getName().getString());
            blockCount++;
        });
        return results;
    }

    private HashMap<BlockPos, String> checkEntities(ServerWorld world, int type, String s) {
        HashMap<BlockPos, String> results = Maps.newLinkedHashMap();
        world.iterateEntities().forEach(entity -> {

            DefaultedList<ItemStack> inventory = DefaultedList.of();
            if (entity instanceof ItemFrameEntity) inventory.add(((ItemFrameEntity) entity).getHeldItemStack());
            else if (entity instanceof ArmorStandEntity) entity.getItemsEquipped().forEach(inventory::add);
            else if (entity instanceof ItemEntity) inventory.add(((ItemEntity) entity).getStack());
            /*
            switch(entity) {
                case ItemFrameEntity e2 -> inventory.add(e2.getHeldItemStack());
                case ArmorStandEntity e2 -> e2.getItemsEquipped().forEach(inventory::add);
                case ItemEntity e2 -> e2.getStack();
                default -> {}
            }
             */
            if (inventory.isEmpty()) return;

            if (checkInventory(inventory, type, s)) results.put(entity.getBlockPos(), ((EntityMixin) entity).getDefaultName().getString());
            entityCount++;
        });

        return results;
    }

    private boolean checkInventory(DefaultedList<ItemStack> inventory, int type, String s) {
        for (ItemStack stack : inventory) {
            switch (type) {
                case 0 -> {
                    if (!Registries.ITEM.getId(stack.getItem()).getPath().equals(s)) continue;
                }
                case 1 -> {
                    if (!stack.getName().getString().toLowerCase().contains(s.toLowerCase())) continue;
                }
                case 2 -> {
                    if (stack.getNbt() == null) continue;
                    if (!stack.getNbt().toString().toLowerCase().contains(s.toLowerCase())) continue;
                }
            }
            return true;
        }
        return false;
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

    public CompletableFuture<Suggestions> getSuggestions(CommandContext context, SuggestionsBuilder builder) {
        Registries.ITEM.forEach(item -> builder.suggest(Registries.ITEM.getId(item).getPath()));
        return builder.buildFuture();
    }
}
