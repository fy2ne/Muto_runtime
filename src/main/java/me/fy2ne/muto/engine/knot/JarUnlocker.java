package me.fy2ne.muto.engine.knot;

import me.fy2ne.muto.MutoLog;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

public final class JarUnlocker {
    private static sun.misc.Unsafe unsafe;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe) f.get(null);
        } catch (Throwable t) {
            MutoLog.warn("unsafe unavailable in JarUnlocker: {}", t.getMessage());
        }
    }

    private JarUnlocker() {}

    public static boolean unlockAndMove(Path source, Path target) {
        if (source == null || target == null) return false;
        if (!Files.exists(source)) return false;

        Path absSource = source.toAbsolutePath().normalize();
        Path absTarget = target.toAbsolutePath().normalize();

        unlock(absSource);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Files.move(absSource, absTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                MutoLog.info("renamed jar (atomic): {} -> {}", absSource.getFileName(), absTarget.getFileName());
                return true;
            } catch (Throwable ex1) {
                try {
                    Files.move(absSource, absTarget, StandardCopyOption.REPLACE_EXISTING);
                    MutoLog.info("renamed jar: {} -> {}", absSource.getFileName(), absTarget.getFileName());
                    return true;
                } catch (Throwable ex2) {
                    if (attempt < 2) {
                        unlock(absSource);
                    } else {
                        MutoLog.warn("failed renaming {} -> {}: {}", absSource.getFileName(), absTarget.getFileName(), ex2.getMessage());
                        me.fy2ne.muto.dev.MutoDev.log("LOCK", "failed renaming " + absSource.getFileName() + ": " + ex2.getMessage());
                        return false;
                    }
                }
            }
        }
        return false;
    }

    public static void unlock(Path jarPath) {
        if (jarPath == null) return;
        Path absPath = jarPath.toAbsolutePath().normalize();
        String fileName = absPath.getFileName().toString();

        openJvmInternalModules();
        closeModContainerRoots(absPath, fileName);
        closeZipFileSystem(absPath);
        closeZipFileSystemProviderMap(absPath, fileName);
        closeKnotClassDelegateMetadata(absPath, fileName);
        closeUcpJarLoaders(absPath, fileName);
        closeJarFileFactory(fileName);
    }

    private static void openJvmInternalModules() {
        try {
            java.lang.instrument.Instrumentation inst = me.fy2ne.muto.engine.agent.MutoAgentBootstrap.getInstrumentation();
            if (inst == null) return;
            Module myMod = JarUnlocker.class.getModule();
            for (Module m : ModuleLayer.boot().modules()) {
                String mn = m.getName();
                if ("java.base".equals(mn) || "jdk.zipfs".equals(mn)) {
                    Map<String, Set<Module>> opens = new HashMap<>();
                    for (String pkg : m.getPackages()) {
                        opens.put(pkg, Set.of(myMod));
                    }
                    try {
                        inst.redefineModule(m, Set.of(), Map.of(), opens, Set.of(), Map.of());
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean matchesJar(String candidate, Path absPath, String fileName) {
        if (candidate == null) return false;
        if (candidate.contains(fileName) || candidate.equalsIgnoreCase(absPath.toString())) return true;
        String encodedName = fileName.replace("%", "%25");
        if (candidate.contains(encodedName)) return true;
        String uriStr = absPath.toUri().toString();
        return candidate.equalsIgnoreCase(uriStr) || candidate.contains(uriStr);
    }

    private static void closeKnotClassDelegateMetadata(Path absPath, String fileName) {
        try {
            Object delegate = KnotInjector.getDelegate();
            if (delegate != null) {
                Field mcField = null;
                try {
                    mcField = delegate.getClass().getDeclaredField("metadataCache");
                } catch (NoSuchFieldException ignored) {}
                if (mcField != null) {
                    Map<?, ?> metaMap = (Map<?, ?>) getFieldValue(mcField, delegate);
                    if (metaMap != null) {
                        synchronized (metaMap) {
                            metaMap.keySet().removeIf(k -> {
                                if (k instanceof Path p) {
                                    return p.toAbsolutePath().normalize().equals(absPath) || p.getFileName().toString().equalsIgnoreCase(fileName);
                                }
                                return matchesJar(String.valueOf(k), absPath, fileName);
                            });
                        }
                    }
                }
                Field csField = null;
                try {
                    csField = delegate.getClass().getDeclaredField("codeSources");
                } catch (NoSuchFieldException ignored) {}
                if (csField != null) {
                    Set<?> cs = (Set<?>) getFieldValue(csField, delegate);
                    if (cs != null) {
                        synchronized (delegate) {
                            Set<Object> copy = new HashSet<>(cs);
                            copy.removeIf(k -> {
                                if (k instanceof Path p) {
                                    return p.toAbsolutePath().normalize().equals(absPath) || p.getFileName().toString().equalsIgnoreCase(fileName);
                                }
                                return matchesJar(String.valueOf(k), absPath, fileName);
                            });
                            setFieldValue(csField, delegate, copy);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("error closing KnotClassDelegate metadata for {}: {}", fileName, t.getMessage());
        }
    }

    private static void closeZipFileSystemProviderMap(Path absPath, String fileName) {
        try {
            for (java.nio.file.spi.FileSystemProvider provider : java.nio.file.spi.FileSystemProvider.installedProviders()) {
                if (provider.getClass().getName().contains("ZipFileSystemProvider")) {
                    Field f = provider.getClass().getDeclaredField("filesystems");
                    Map<?, ?> map = (Map<?, ?>) getFieldValue(f, provider);
                    if (map != null) {
                        synchronized (map) {
                            List<Object> toClose = new ArrayList<>();
                            for (Map.Entry<?, ?> entry : map.entrySet()) {
                                Object key = entry.getKey();
                                String kStr = String.valueOf(key);
                                if (matchesJar(kStr, absPath, fileName)) {
                                    toClose.add(entry.getValue());
                                }
                            }
                            for (Object fsObj : toClose) {
                                if (fsObj instanceof FileSystem fs && fs.isOpen()) {
                                    try {
                                        fs.close();
                                        MutoLog.info("closed ZipFileSystem from ZipFileSystemProvider for {}", fileName);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("error closing ZipFileSystemProvider map for {}: {}", fileName, t.getMessage());
        }
    }

    private static void closeModContainerRoots(Path absPath, String fileName) {
        try {
            var loader = FabricLoader.getInstance();
            if (loader == null) return;
            for (ModContainer mc : loader.getAllMods()) {
                boolean matches = false;
                try {
                    for (Path p : mc.getOrigin().getPaths()) {
                        if (p.toAbsolutePath().normalize().equals(absPath) || p.getFileName().toString().equalsIgnoreCase(fileName)) {
                            matches = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {}

                if (!matches) continue;

                Class<?> cls = mc.getClass();
                while (cls != null && cls != Object.class) {
                    try {
                        Field rootsField = cls.getDeclaredField("roots");
                        rootsField.setAccessible(true);
                        List<?> roots = (List<?>) getFieldValue(rootsField, mc);
                        if (roots != null) {
                            for (Object rootObj : roots) {
                                if (rootObj instanceof Path p) {
                                    try {
                                        FileSystem fs = p.getFileSystem();
                                        if (fs != null && fs.isOpen() && !fs.equals(FileSystems.getDefault())) {
                                            fs.close();
                                            MutoLog.info("closed ModContainer ZipFileSystem for {}", fileName);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                            setFieldValue(rootsField, mc, null);
                        }
                    } catch (NoSuchFieldException ignored) {}
                    cls = cls.getSuperclass();
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("error closing ModContainer roots for {}: {}", fileName, t.getMessage());
        }
    }

    private static void closeZipFileSystem(Path absPath) {
        try {
            URI uri = URI.create("jar:" + absPath.toUri());
            FileSystem fs = FileSystems.getFileSystem(uri);
            if (fs != null && fs.isOpen()) {
                fs.close();
                MutoLog.info("closed ZipFileSystem via URI for {}", absPath.getFileName());
            }
        } catch (Throwable ignored) {}
    }

    private static void closeUcpJarLoaders(Path absPath, String fileName) {
        Set<ClassLoader> loaders = new HashSet<>();
        loaders.add(KnotInjector.getKnotClassLoader());
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ClassLoader.getSystemClassLoader());

        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            closeInClassLoader(cl, absPath, fileName);
        }
    }

    private static void closeInClassLoader(ClassLoader cl, Path absPath, String fileName) {
        try {
            Object target = cl;
            try {
                Field ulf = cl.getClass().getDeclaredField("urlLoader");
                Object ul = getFieldValue(ulf, cl);
                if (ul != null) {
                    target = ul;
                }
            } catch (NoSuchFieldException ignored) {}

            Class<?> cur = target.getClass();
            Field ucpField = null;
            while (cur != null && cur != Object.class) {
                try {
                    ucpField = cur.getDeclaredField("ucp");
                    break;
                } catch (NoSuchFieldException ignored) {}
                cur = cur.getSuperclass();
            }

            if (ucpField == null) return;
            Object ucp = getFieldValue(ucpField, target);
            if (ucp == null) return;

            Class<?> ucpClass = ucp.getClass();
            try {
                Field pathField = ucpClass.getDeclaredField("path");
                List<?> pathList = (List<?>) getFieldValue(pathField, ucp);
                if (pathList != null) {
                    synchronized (ucp) {
                        pathList.removeIf(u -> matchesJar(String.valueOf(u), absPath, fileName));
                    }
                }
            } catch (Throwable ignored) {}

            try {
                Field unopenedField = ucpClass.getDeclaredField("unopenedUrls");
                java.util.Collection<?> unopened = (java.util.Collection<?>) getFieldValue(unopenedField, ucp);
                if (unopened != null) {
                    synchronized (unopened) {
                        unopened.removeIf(u -> matchesJar(String.valueOf(u), absPath, fileName));
                    }
                }
            } catch (Throwable ignored) {}

            Field loadersField = null;
            try {
                loadersField = ucpClass.getDeclaredField("loaders");
            } catch (NoSuchFieldException ignored) {}

            if (loadersField == null) return;
            List<?> loadersList = (List<?>) getFieldValue(loadersField, ucp);
            if (loadersList == null) return;

            synchronized (loadersList) {
                List<Object> toRemove = new ArrayList<>();
                for (Object loader : loadersList) {
                    if (loader == null) continue;
                    try {
                        Field jarField = null;
                        Class<?> lClass = loader.getClass();
                        while (lClass != null && lClass != Object.class) {
                            try {
                                jarField = lClass.getDeclaredField("jar");
                                break;
                            } catch (NoSuchFieldException ignored) {}
                            lClass = lClass.getSuperclass();
                        }

                        if (jarField != null) {
                            JarFile jf = (JarFile) getFieldValue(jarField, loader);
                            if (jf != null) {
                                String jfName = jf.getName();
                                if (matchesJar(jfName, absPath, fileName)) {
                                    jf.close();
                                    setFieldValue(jarField, loader, null);
                                    toRemove.add(loader);
                                    MutoLog.info("closed UCP JarLoader handle for {}", fileName);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                loadersList.removeAll(toRemove);
            }

            Field lmapField = null;
            try {
                lmapField = ucpClass.getDeclaredField("lmap");
            } catch (NoSuchFieldException ignored) {}
            if (lmapField != null) {
                Map<?, ?> lmap = (Map<?, ?>) getFieldValue(lmapField, ucp);
                if (lmap != null) {
                    synchronized (lmap) {
                        lmap.entrySet().removeIf(e -> {
                            String k = String.valueOf(e.getKey());
                            if (matchesJar(k, absPath, fileName)) {
                                Object val = e.getValue();
                                if (val != null) {
                                    try {
                                        java.lang.reflect.Method closeM = val.getClass().getMethod("close");
                                        closeM.setAccessible(true);
                                        closeM.invoke(val);
                                    } catch (Throwable ignored) {}
                                }
                                return true;
                            }
                            return false;
                        });
                    }
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("error closing UCP loaders for {}: {}", fileName, t.getMessage());
        }
    }

    private static void closeJarFileFactory(String fileName) {
        try {
            Class<?> jffClass = Class.forName("sun.net.www.protocol.jar.JarFileFactory");
            Field instanceField = jffClass.getDeclaredField("instance");
            instanceField.setAccessible(true);
            Object instance = getFieldValue(instanceField, null);
            if (instance != null) {
                Field fcField = jffClass.getDeclaredField("fileCache");
                fcField.setAccessible(true);
                Map<?, ?> fileCache = (Map<?, ?>) getFieldValue(fcField, instance);

                Field ucField = jffClass.getDeclaredField("urlCache");
                ucField.setAccessible(true);
                Map<?, ?> urlCache = (Map<?, ?>) getFieldValue(ucField, instance);

                String encodedName = fileName.replace("%", "%25");
                if (fileCache != null) {
                    synchronized (fileCache) {
                        List<Object> keysToRemove = new ArrayList<>();
                        for (Map.Entry<?, ?> entry : fileCache.entrySet()) {
                            String k = String.valueOf(entry.getKey());
                            if (k.contains(fileName) || k.contains(encodedName)) {
                                keysToRemove.add(entry.getKey());
                                if (entry.getValue() instanceof JarFile jf) {
                                    try {
                                        jf.close();
                                        MutoLog.info("closed JarFileFactory cached JarFile for {}", fileName);
                                    } catch (Throwable ignored) {}
                                    if (urlCache != null) urlCache.remove(jf);
                                }
                            }
                        }
                        for (Object k : keysToRemove) {
                            fileCache.remove(k);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Object getFieldValue(Field f, Object obj) {
        if (unsafe != null) {
            try {
                long offset = (obj == null) ? unsafe.staticFieldOffset(f) : unsafe.objectFieldOffset(f);
                Object base = (obj == null) ? unsafe.staticFieldBase(f) : obj;
                return unsafe.getObject(base, offset);
            } catch (Throwable ignored) {}
        }
        try {
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {}
        return null;
    }

    private static void setFieldValue(Field f, Object obj, Object value) {
        if (unsafe != null) {
            try {
                long offset = (obj == null) ? unsafe.staticFieldOffset(f) : unsafe.objectFieldOffset(f);
                Object base = (obj == null) ? unsafe.staticFieldBase(f) : obj;
                unsafe.putObject(base, offset, value);
                return;
            } catch (Throwable ignored) {}
        }
        try {
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {}
    }
}
