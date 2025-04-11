package net.itemfinder.main.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class IFConfigScreen {

    private static final IFConfig config = IFConfig.INSTANCE;

    public static Screen create(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .save(IFConfig.INSTANCE::write)
                .title(Text.literal("Item Finder"))

                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Settings"))

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Auto-confirm global search"))
                                .binding(false, () -> config.autoConfirm, newVal -> config.autoConfirm = newVal)
                                .controller(TickBoxControllerBuilder::create).build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Scan item displays"))
                                .binding(false, () -> config.scanItemDisplays, newVal -> config.scanItemDisplays = newVal)
                                .controller(TickBoxControllerBuilder::create).build())

                        .option(Option.<HandSearchMode>createBuilder()
                                .name(Text.literal("Handheld search mode"))
                                .binding(HandSearchMode.Name, () -> HandSearchMode.valueOf(config.handSearchMode), newVal -> config.handSearchMode = newVal.name())
                                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HandSearchMode.class)).build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Suggest vanilla loot tables"))
                                .binding(false, () -> config.suggestVanillaLootTables, newVal -> config.suggestVanillaLootTables = newVal)
                                .controller(TickBoxControllerBuilder::create).build())
                        .build())
                .build()
                .generateScreen(parent);
    }

    public enum HandSearchMode {
        Id,
        Name
    }
}
