package me.fy2ne.muto.mixin;

import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.mods.ModStateManager;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Mixin(value = Screen.class, priority = 9999)
public abstract class ScreenMixin {

    @Shadow
    @Final
    private List<GuiEventListener> children;

    @Shadow
    @Final
    private List<Renderable> renderables;

    @Shadow
    protected abstract void removeWidget(GuiEventListener listener);

    @Inject(method = "init(II)V", at = @At("RETURN"))
    private void muto$purgeDisabledModWidgets(int width, int height, CallbackInfo ci) {
        if (this.children == null || this.children.isEmpty()) return;

        Set<String> disabledMods = ModStateManager.INSTANCE.getDisabledModIds();
        if (disabledMods.isEmpty()) return;

        List<GuiEventListener> snapshot = new ArrayList<>(this.children);
        for (GuiEventListener listener : snapshot) {
            purgeIfDisabled(listener, disabledMods);
        }
    }

    private void purgeIfDisabled(GuiEventListener listener, Set<String> disabledMods) {
        if (listener == null) return;

        // Traverse nested containers (e.g. PageListWidget, AbstractSelectionList, etc.)
        if (listener instanceof net.minecraft.client.gui.components.events.ContainerEventHandler ceh) {
            try {
                List<? extends GuiEventListener> sub = ceh.children();
                if (sub != null && !sub.isEmpty()) {
                    List<GuiEventListener> subCopy = new ArrayList<>(sub);
                    for (GuiEventListener child : subCopy) {
                        purgeIfDisabled(child, disabledMods);
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (isBelongingToDisabled(listener, disabledMods)) {
            if (listener instanceof AbstractWidget aw) {
                aw.visible = false;
                aw.active = false;
            }
            this.removeWidget(listener);
            if (this.renderables != null && listener instanceof Renderable r) {
                this.renderables.remove(r);
            }
            MutoLog.info("purged ghost widget from disabled mod: {}", listener.getClass().getName());
        }
    }

    private static boolean isBelongingToDisabled(Object obj, Set<String> disabledMods) {
        if (obj == null || disabledMods.isEmpty()) return false;

        String className = obj.getClass().getName();
        if (className.startsWith("net.minecraft.") ||
            className.startsWith("com.mojang.") ||
            className.startsWith("net.fabricmc.") ||
            className.startsWith("me.fy2ne.muto.")) {
            return false;
        }

        String lower = className.toLowerCase(Locale.ROOT);
        for (String modId : disabledMods) {
            if (modId == null || modId.isEmpty()) continue;
            String cleanId = modId.toLowerCase(Locale.ROOT).replace('-', '_');
            if (lower.contains("." + cleanId + ".") ||
                lower.contains("." + cleanId + "$") ||
                lower.startsWith(cleanId + ".")) {
                return true;
            }
        }

        // Check if any declared field holds an action or target belonging to a disabled mod
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class && !c.getName().startsWith("java.")) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val != null) {
                        String valClass = val.getClass().getName().toLowerCase(Locale.ROOT);
                        for (String modId : disabledMods) {
                            if (modId == null || modId.isEmpty()) continue;
                            String cleanId = modId.toLowerCase(Locale.ROOT).replace('-', '_');
                            if (valClass.contains("." + cleanId + ".") ||
                                valClass.contains("." + cleanId + "$") ||
                                valClass.startsWith(cleanId + ".")) {
                                return true;
                            }
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return false;
    }
}
