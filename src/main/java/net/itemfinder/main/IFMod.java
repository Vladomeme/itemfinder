package net.itemfinder.main;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class IFMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("itemfinder");

    public static KeyBinding teleportKey;
    public static KeyBinding handSearchKey;
    public static KeyBinding handGlobalSearchKey;

    @Override
    public void onInitialize() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {

            @Override
            public Identifier getFabricId() {
                return Identifier.of("itemfinder", "assets");
            }

            @Override
            public void reload(ResourceManager manager) {
                addCommand();
            }
        });

        teleportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Teleport to next result",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "Item Finder"));
        handSearchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Search for held item",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "Item Finder"));
        handGlobalSearchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Global search for held item",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "Item Finder"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (teleportKey.wasPressed()) Controller.teleportToNext();
            if (handSearchKey.wasPressed()) ItemFinder.searchHandheld(false);
            if (handGlobalSearchKey.wasPressed()) ItemFinder.searchHandheld(true);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Controller.shutdown());

        ItemFinder.ERROR_STACK.applyComponentsFrom(ComponentMap.builder()
                .add(DataComponentTypes.CUSTOM_NAME, Text.of("Unknown")).build());

        LOGGER.info("Item Finder loaded!");
    }

    public void addCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("finditem")
                        .then(literal("id")
                                .then(argument("id", StringArgumentType.string())
                                .suggests(ItemFinder::getSuggestions)
                                .executes(context -> ItemFinder.search(0, StringArgumentType.getString(context, "id").toLowerCase(), context))
                                        .then(literal("global")
                                        .executes(context -> ItemFinder.prepareGlobalSearch(0, StringArgumentType.getString(context, "id"), context))
                        )))
                        .then(literal("name")
                                .then(argument("name", StringArgumentType.string())
                                .executes(context -> ItemFinder.search(1, StringArgumentType.getString(context, "name").toLowerCase(), context))
                                        .then(literal("global")
                                        .executes(context -> ItemFinder.prepareGlobalSearch(1, StringArgumentType.getString(context, "name"), context))
                        )))
                        .then(literal("data")
                                .then(argument("data", StringArgumentType.string())
                                .executes(context -> ItemFinder.search(2, StringArgumentType.getString(context, "data").toLowerCase(), context))
                                        .then(literal("global")
                                        .executes(context -> ItemFinder.prepareGlobalSearch(2, StringArgumentType.getString(context, "data"), context))
                        )))
                        .then(literal("loot_table")
                                .then(argument("name", StringArgumentType.string())
                                .suggests(LootTableFinder::getSuggestions)
                                .executes(context -> LootTableFinder.search(StringArgumentType.getString(context, "name").toLowerCase(), context))
                                        .then(literal("global")
                                        .executes(context -> LootTableFinder.prepareGlobalSearch(StringArgumentType.getString(context, "name"), context))
                        )))
                        .then(literal("stop")
                                .executes(Controller::stop))
                        .then(literal("confirm")
                                .executes(context -> Controller.confirm()))));
    }
}
