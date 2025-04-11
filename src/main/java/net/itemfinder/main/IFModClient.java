package net.itemfinder.main;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class IFModClient implements ClientModInitializer {

    public static KeyBinding teleportKey;
    public static KeyBinding handSearchKey;
    public static KeyBinding handGlobalSearchKey;

    @Override
    public void onInitializeClient() {
        teleportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Teleport to next result",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "Item Finder"));
        handSearchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Search for held item",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "Item Finder"));
        handGlobalSearchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("Global search for held item",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "Item Finder"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (teleportKey.wasPressed()) ClientController.teleportToNext(client);
            if (handSearchKey.wasPressed()) ClientController.searchHandheld(client, false);
            if (handGlobalSearchKey.wasPressed()) ClientController.searchHandheld(client, true);
        });

        IFMod.LOGGER.info("Item Finder loaded!");
    }
}
