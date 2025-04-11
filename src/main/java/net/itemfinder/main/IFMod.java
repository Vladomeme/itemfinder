package net.itemfinder.main;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.itemfinder.main.config.IFConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

@Environment(EnvType.SERVER)
public class IFMod implements DedicatedServerModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("itemfinder");

    @Override
    public void onInitializeServer() {
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
                                        .executes(context -> LootTableFinder.prepareGlobalSearch(StringArgumentType.getString(context, "name"), context)))))
                        .then(literal("stop")
                                .executes(Controller::stop))
                        .then(literal("next")
                                .executes((Controller::teleportToNext)))
                        .then(literal("confirm")
                                .executes(Controller::confirm))));

        ServerLifecycleEvents.SERVER_STOPPING.register(client -> {
            if (!IFConfig.FILE.exists()) IFConfig.INSTANCE.write();
            Controller.shutdown();
        });
    }
}
