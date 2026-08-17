package com.skysoft.mixin;

import com.skysoft.features.inventory.AnimatedDyeArmorCache;
import com.skysoft.utils.mixin.MixinErrorBoundary;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Inventory.class)
public abstract class AnimatedDyeArmorInventoryMixin {
    @ModifyVariable(method = "setItem", at = @At("HEAD"), argsOnly = true)
    private ItemStack skysoftRepairArmor(ItemStack value, int slot, ItemStack stack) {
        return MixinErrorBoundary.value(
            "Animated dye armor repair",
            value,
            () -> AnimatedDyeArmorCache.repair((Inventory) (Object) this, slot, value)
        );
    }
}
