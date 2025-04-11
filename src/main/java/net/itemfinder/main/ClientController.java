package net.itemfinder.main;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.itemfinder.main.config.IFConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

@Environment(EnvType.CLIENT)
public class ClientController {

    /**
     * Used on client on keybind press, requests teleport to next search result.
     */
    public static void teleportToNext(MinecraftClient client) {
        //noinspection StatementWithEmptyBody
        while (IFModClient.teleportKey.wasPressed());
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        if (nh != null) nh.sendCommand("finditem next");
    }

    /**
     * Starts an item search with parameters taken from a currently held item. Search mode depends on the config.
     * Normal and Global modes are called with the corresponding keybinds.
     */
    public static void searchHandheld(MinecraftClient client, boolean global) {
        //noinspection StatementWithEmptyBody
        while (IFModClient.handSearchKey.wasPressed());

        ClientPlayerEntity player = client.player;
        if (player == null) return;
        if (!player.isCreative()) {
            player.sendMessage(Text.of("Handheld search can only be started in creative."), false);
            return;
        }

        ItemStack stack = player.getMainHandStack();
        if (stack == ItemStack.EMPTY) return;

        String s = switch (IFConfig.INSTANCE.handSearchMode) {
            case "Id" -> player.getMainHandStack().getItem().toString();
            case "Name" -> player.getMainHandStack().getName().getString().toLowerCase();
            default -> throw new IllegalStateException("Unexpected hand search mode value: " + IFConfig.INSTANCE.handSearchMode + ". Only \"Name\" and \"Id\" is allowed.");
        };
        String mode = IFConfig.INSTANCE.handSearchMode.toLowerCase();

        player.sendMessage(Text.literal("Requesting search for " + s).setStyle(Style.EMPTY.withColor(Formatting.YELLOW)), false);

        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler != null) handler.sendCommand("finditem " + mode + " \"" + s + (global ? "\" global" : "\""));
    }
}
