package net.itemfinder.main;

import net.itemfinder.main.config.IFConfig;
import net.minecraft.util.math.BlockPos;

public abstract class AbstractSearchResult {

    String name;
    BlockPos pos;

    public static int compare(AbstractSearchResult o1, AbstractSearchResult o2) {
        switch (IFConfig.INSTANCE.sortMode) {
            case "Coords" -> {
                int result = Integer.compare(o1.pos.getX(), o2.pos.getX());
                if (result != 0) return result;
                result = Integer.compare(o1.pos.getZ(), o2.pos.getZ());
                if (result != 0) return result;
                return Integer.compare(o1.pos.getY(), o2.pos.getY());
            }
            case "Name" -> {
                return o1.name.compareTo(o2.name);
            }
            default -> {
                return 0;
            }
        }
    }
}
