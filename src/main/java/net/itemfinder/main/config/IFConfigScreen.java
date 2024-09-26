package net.itemfinder.main.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class IFConfigScreen {
        public static Screen create(Screen parent) {

                IFConfig currentConfig = IFConfig.INSTANCE, defaultConfig = new IFConfig();

                ConfigBuilder builder = ConfigBuilder.create()
                        .setParentScreen(parent)
                        .setTitle(Text.of("Item Finder"))
                        .setSavingRunnable(currentConfig::write);

                ConfigCategory category = builder.getOrCreateCategory(Text.of("Settings"));
                ConfigEntryBuilder entryBuilder = builder.entryBuilder();

                category.addEntry(entryBuilder.startBooleanToggle(Text.of("Auto-confirm global search"), currentConfig.autoConfirm)
                        .setSaveConsumer(newConfig -> currentConfig.autoConfirm = newConfig)
                        .setDefaultValue(defaultConfig.autoConfirm)
                        .build());

                category.addEntry(entryBuilder.startBooleanToggle(Text.of("Check item displays"), currentConfig.scanItemDisplays)
                        .setSaveConsumer(newConfig -> currentConfig.scanItemDisplays = newConfig)
                        .setDefaultValue(defaultConfig.scanItemDisplays)
                        .build());

                return builder.build();
        }
}
