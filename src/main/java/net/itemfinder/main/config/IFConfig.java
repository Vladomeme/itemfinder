package net.itemfinder.main.config;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;

public class IFConfig {

    public boolean autoConfirm = false;
    public boolean scanItemDisplays = false;
    public String handSearchMode = "Name";
    public boolean suggestVanillaLootTables = false;

    public static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "itemfinder.json");

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
}
