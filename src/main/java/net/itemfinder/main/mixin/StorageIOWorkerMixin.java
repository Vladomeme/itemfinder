package net.itemfinder.main.mixin;

import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StorageIoWorker.class)
public interface StorageIOWorkerMixin {

	@Accessor("storage")
	RegionBasedStorage getStorage();
}

