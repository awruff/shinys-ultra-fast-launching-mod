package org.codeberg.awruff.ultrafastlaunch;

import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class UltraFastLaunchPlugin implements IMixinConfigPlugin {
    private static boolean installed;

    @Override
    public void onLoad(String mixinPackage) {
        if (installed || Boolean.getBoolean("ultrafastlaunch.disable")) return;
        installed = true;

        if (!"false".equals(System.getProperty("ultrafastlaunch.prewarm"))) {
            try {
                Prewarmer.start();
            } catch (Throwable t) {
                Log.debug(LogCategory.GENERAL, "[UltraFastLaunch] Could not start prewarm", t);
            }
        }
        AotCache.start();

        try {
            ClassLoader knot = FabricLauncherBase.getLauncher().getTargetClassLoader();
            Object delegate = findField(knot.getClass(), t -> t.getSimpleName().equals("KnotClassDelegate"), "KnotClassDelegate").get(knot);
            Field transformerField = findField(delegate.getClass(), t -> t == IMixinTransformer.class, "IMixinTransformer");
            IMixinTransformer original = (IMixinTransformer) transformerField.get(delegate);
            if (original == null || Proxy.isProxyClass(original.getClass())) return;

            boolean bypass = Boolean.getBoolean("ultrafastlaunch.bypass");
            TransformCache cache = bypass ? null : TransformCache.open();
            Benchmark benchmark = new Benchmark(bypass ? "bypass" : "cached");
            IMixinTransformer proxy = (IMixinTransformer) Proxy.newProxyInstance(
                    IMixinTransformer.class.getClassLoader(),
                    new Class<?>[]{IMixinTransformer.class},
                    new CachingTransformer(original, cache, benchmark));
            transformerField.set(delegate, proxy);
        } catch (Throwable t) {
            Log.warn(LogCategory.MIXIN, "[UltraFastLaunch] Could not install transform cache, launching normally", t);
        }
    }

    private static Field findField(Class<?> owner, Predicate<Class<?>> type, String typeName) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && type.test(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException(typeName + " in " + owner.getName());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
