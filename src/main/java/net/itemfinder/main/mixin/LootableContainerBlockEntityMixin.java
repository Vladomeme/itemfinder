package net.itemfinder.main.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LootableContainerBlockEntity.class)
public interface LootableContainerBlockEntityMixin {

	@Accessor("lootTable")
	RegistryKey<LootTable> getLootTable();
}

