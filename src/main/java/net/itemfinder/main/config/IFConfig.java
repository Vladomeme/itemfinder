package net.itemfinder.main.config;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;

public class IFConfig {

    public boolean autoConfirm = false;
    public boolean scanItemDisplays = false;
    public HandSearchMode handSearchMode = HandSearchMode.Name;
    public boolean suggestVanillaLootTables = false;

    private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "itemfinder.json");

    public static final IFConfig INSTANCE = read();

    public static IFConfig read() {
        if (!FILE.exists())
            return new IFConfig().write();

        Reader reader = null;
        try {
            return new Gson().fromJson(reader = new FileReader(FILE), IFConfig.class);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            IOUtils.closeQuietly(reader);
        }
    }

    public IFConfig write() {
        Gson gson = new Gson();
        JsonWriter writer = null;
        try {
            writer = gson.newJsonWriter(new FileWriter(FILE));
            writer.setIndent("    ");
            gson.toJson(gson.toJsonTree(this, IFConfig.class), writer);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            IOUtils.closeQuietly(writer);
        }
        return this;
    }

    public Screen create(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .save(this::write)
                .title(Text.literal("Item Finder"))

                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Settings"))

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Auto-confirm global search"))
                                .binding(false, () -> autoConfirm, newVal -> autoConfirm = newVal)
                                .controller(TickBoxControllerBuilder::create).build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Scan item displays"))
                                .binding(false, () -> scanItemDisplays, newVal -> scanItemDisplays = newVal)
                                .controller(TickBoxControllerBuilder::create).build())

                        .option(Option.<HandSearchMode>createBuilder()
                                .name(Text.literal("Handheld search mode"))
                                .binding(HandSearchMode.Name, () -> handSearchMode, newVal -> handSearchMode = newVal)
                                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HandSearchMode.class)).build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Suggest vanilla loot tables"))
                                .binding(false, () -> suggestVanillaLootTables, newVal -> suggestVanillaLootTables = newVal)
                                .controller(TickBoxControllerBuilder::create).build())
                        .build())
                .build()
                .generateScreen(parent);
    }

    public enum HandSearchMode implements NameableEnum {
        Id,
        Name;

        @Override
        public Text getDisplayName() {
            return Text.literal(name().toLowerCase());
        }
    }
}
