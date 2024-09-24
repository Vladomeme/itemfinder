package net.itemfinder.main.mixin;

import net.minecraft.world.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.IntBuffer;

@Mixin(RegionFile.class)
public interface RegionFileMixin {

	@Accessor("sectorData")
	IntBuffer getSectorData();
}

