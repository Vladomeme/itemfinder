package net.itemfinder.main.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LootableContainerBlockEntity.class)
public interface LootableContainerBlockEntityMixin {

	@Invoker("getInvStackList")
	DefaultedList<ItemStack> getInvStackList();

	@Accessor("lootTableId")
	Identifier getLootTable();
}

