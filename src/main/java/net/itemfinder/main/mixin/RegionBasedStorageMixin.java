package net.itemfinder.main.mixin;

import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(RegionBasedStorage.class)
public interface RegionBasedStorageMixin {

	@Accessor("storageKey")
	StorageKey getStorageKey();

	@Accessor("directory")
	Path getDirectory();

	@Accessor("dsync")
	boolean getDsync();
}

