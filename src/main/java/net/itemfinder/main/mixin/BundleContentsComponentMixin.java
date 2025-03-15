package net.itemfinder.main.mixin;

import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(BundleContentsComponent.class)
public interface BundleContentsComponentMixin {

	@Accessor("stacks")
	List<ItemStack> getStacks();
}
