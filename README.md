<div align="center">

<img src="src/main/resources/assets/muto/logo-nobg.png" width="112" height="112" alt="Muto Runtime Logo" />

# Muto Runtime

**Runtime bytecode retransformation, classloader mutation, and live mod lifecycle engine for Fabric.**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.x-E52C29?style=flat-square&logo=minecraft&logoColor=white)](https://fabricmc.net/)
[![Platform](https://img.shields.io/badge/Platform-Fabric-E52C29?style=flat-square)](https://fabricmc.net/)
[![Version](https://img.shields.io/badge/Version-0.1.0--beta-E52C29?style=flat-square)](https://github.com/fy2ne/Muto_runtime/releases)
[![Java](https://img.shields.io/badge/Java-25%2B-E52C29?style=flat-square)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-LGPL--3.0--only-18181b?style=flat-square)](LICENSE)

[Documentation](https://muto.fy2ne.me) · [Modrinth](https://modrinth.com/mod/muto) · [Issues](https://github.com/fy2ne/Muto_runtime/issues)

</div>

---

## Overview

Muto Runtime (`me.fy2ne.muto`) is a client-side **developer engine library** providing JVM-level hot-swap primitives for Fabric mods — allowing modules to be injected, toggled, unloaded, and updated at runtime without restarting the JVM.

It combines four coordinated runtime subsystems:

1. **Dynamic JVMTI Instrumentation & Live Mixin Retransformation** (`MutoAgentBootstrap`, `MutoClassFileTransformer`, `DynamicMixinEngine`) — self-attaches a Java Instrumentation agent (`Can-Retransform-Classes`) to weave and unweave mixin bytecode directly inside already-loaded classes via `Instrumentation.retransformClasses()`.
2. **Knot ClassLoader Topology & OS Handle Severance** (`KnotInjector`, `JarUnlocker`) — injects jar/JiJ paths into Fabric's `KnotClassLoader` delegate and closes internal `URLClassPath`, `ZipFileSystem`, and `JarFileFactory` locks via `Unsafe` field-offset release.
3. **Registry Invariant Thawing & Namespace Isolation** (`RegistryFreezer`, `DiContainerGuard`) — clears `MappedRegistry` freeze flags around isolated per-entrypoint lifecycles, prunes invalidated namespaces on teardown, and guarantees refreezing in `finally` invariants.
4. **Transactional Rollback & Render-Safe Remounting** (`ReloadEngine`, `ResourceHotMount`, `ReloadBoundaryManager`) — snapshots directory hashes via SHA-256, rolls back failed swaps automatically, and guards shader/pack reloads against active overlay locks.

---

## Integration (For Mod Developers)

Declare Muto Runtime as a compile-only soft dependency (`modCompileOnly`) and guard calls behind `FabricLoader.getInstance().isModLoaded("muto")`.

### Gradle

```groovy
repositories {
    mavenLocal()
    maven {
        name = "Modrinth"
        url = "https://api.modrinth.com/maven"
    }
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/fy2ne/Muto_runtime")
    }
}

dependencies {
    modCompileOnly "maven.modrinth:muto:0.1.0-beta+26.x"
}
```

### Trigger a Reload

```java
import net.fabricmc.loader.api.FabricLoader;
import me.fy2ne.muto.api.MutoApi;

public class DownloaderIntegration {
    public static void onModDownloaded(java.nio.file.Path jar) {
        if (!FabricLoader.getInstance().isModLoaded("muto")) return;

        if (MutoApi.isSafeToReload()) {
            MutoApi.reloadAsync(stage -> System.out.println(stage))
                .thenAccept(result -> {
                    if (result.success()) {
                        System.out.println("hot-swapped in " + result.durationMs() + "ms");
                    } else {
                        System.err.println("swap failed: " + result.error());
                    }
                });
        }
    }
}
```

### Hook Lifecycle Events

```java
import me.fy2ne.muto.api.MutoEvents;

MutoEvents.RELOAD_START.register((startMs, diff) -> {
    // close stale network connections, clear texture caches
});

MutoEvents.RELOAD_FINISH.register((endMs, diff, success, durationMs, error) -> {
    // refresh UI, re-bind resources, update lists
});
```

---

## API Surface

| Method | Returns | Notes |
|---|---|---|
| `MutoApi.isAvailable()` | `boolean` | Runtime engine initialized |
| `MutoApi.isReloading()` | `boolean` | Reload transaction currently active |
| `MutoApi.isSafeToReload()` | `boolean` | Title/boundary state, no active world |
| `MutoApi.plan()` | `ReloadPlan` | Non-mutating `/mods` SHA-256 diff |
| `MutoApi.reloadAsync()` | `CompletableFuture<ReloadResult>` | Asynchronous hot-swap execution |
| `MutoApi.reloadAsync(notifier)` | `CompletableFuture<ReloadResult>` | Asynchronous reload with stage callbacks |
| `MutoApi.reloadWithBoundary(screen)` | `void` | Safe world/server exit, swap, and reconnect |
| `MutoApi.getHistory()` | `List<ReloadHistoryEntry>` | Audited transaction history |
| `MutoApi.getReloadableModIds()` | `Set<String>` | Dynamic mods in active snapshot |
| `MutoApi.getStubbornModIds()` | `Set<String>` | Protected core mods requiring JVM restart |

---

## Building & Testing

```bash
./gradlew build
./gradlew test
./gradlew publishToMavenLocal
```

- **Target**: Minecraft `>=26.1 <27.0` (`26.3`), Fabric Loader `>=0.16.0`, Fabric API `>=0.144.1`, Java `25+` (client environment).
- **License**: `LGPL-3.0-only` — see [`LICENSE`](LICENSE).
