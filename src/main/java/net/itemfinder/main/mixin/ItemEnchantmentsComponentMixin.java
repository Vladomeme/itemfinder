package net.itemfinder.main.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemEnchantmentsComponent.class)
public interface ItemEnchantmentsComponentMixin {

	@Accessor("enchantments")
	Object2IntOpenHashMap<RegistryEntry<Enchantment>> enchantments();
}