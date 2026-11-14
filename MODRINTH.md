<div align="center">

<img src="https://raw.githubusercontent.com/fy2ne/Muto_runtime/26.x/src/main/resources/assets/muto/logo-nobg.png" width="112" height="112" alt="Muto Runtime Logo" />

# Muto Runtime

**Runtime bytecode retransformation, classloader mutation, and live mod lifecycle engine for Fabric.**

[![Minecraft Java Edition](https://img.shields.io/badge/Minecraft-26.x-E52C29?style=flat-square&logo=minecraft&logoColor=white)](https://fabricmc.net/)
[![Fabric](https://img.shields.io/badge/Platform-Fabric-E52C29?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-E52C29?style=flat-square)](https://openjdk.org/)
[![Release](https://img.shields.io/badge/Version-0.1.0--beta-E52C29?style=flat-square)](https://github.com/fy2ne/Muto_runtime/releases)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL--3.0--only-18181b?style=flat-square)](https://github.com/fy2ne/Muto_runtime/blob/26.x/LICENSE)

</div>

---

## Abstract

Mod loading inside the Minecraft Java Edition runtime has traditionally behaved as a **monotonic, boot-locked state machine**: once Fabric Loader's `KnotClassLoader` constructs its class graph, SpongePowered Mixin completes load-time bytecode transformation, and Mojang invokes `MappedRegistry.freeze()`, adding, disabling, or hot-swapping executable modules requires terminating the JVM process.

**Muto Runtime** (`me.fy2ne.muto`) is a client-side **runtime systems library** — not a player-facing UI mod — that provides deterministic JVM-level primitives for **live mod injection, dynamic mixin weaving/unweaving, registry thawing, and isolated module teardown** within a running Minecraft client.

---

## Systems Architecture

Muto executes live state transitions across four coordinated subsystems:

### 1. Dynamic JVMTI Instrumentation & Live Mixin Retransformation
- **Self-Attaching Agent (`MutoAgentBootstrap` / `MutoAgent`)**: Dynamically attaches to the running client PID via the Java Attach API (`agentmain`) with `Can-Retransform-Classes` and `Can-Redefine-Classes` capabilities enabled.
- **Bytecode Baseline & Live Transformer (`MutoClassFileTransformer` / `DynamicMixinEngine`)**: Indexes baseline class bytecode prior to dynamic mutations, parses mixin descriptors from newly injected or removed jars, weaves or strips mixin transformations from **already-loaded classes in memory** via `Instrumentation.retransformClasses()`, and restores pre-mutation bytecode cleanly on mod unload.

### 2. Knot ClassLoader Topology & OS Handle Severance
- **Live Classpath Injection (`KnotInjector`)**: Appends incoming `.jar` archives and nested Jar-in-Jar (`META-INF/jars`) libraries directly into Fabric Loader's active `Knot` class delegate and URL search paths, resolving transitive classes without restarting the JVM.
- **OS Handle Unlocking (`JarUnlocker`)**: On Windows (NTFS) and POSIX hosts, open `ZipFile`, `URLClassPath`, `ZipFileSystem`, and `JarFileFactory` caches prevent external renaming or removal of active mod jars. Muto uses `sun.misc.Unsafe` field-offset access and `Instrumentation.redefineModule()` to close lingering archive handles on unload, backed by a deterministic detached rename fallback and `preLaunch` reconciliation pass.

### 3. Registry Invariant Thawing & Namespace Isolation
- **Freeze/Thaw Guard (`RegistryFreezer`)**: Unlocks frozen `MappedRegistry` instances across `BuiltInRegistries.REGISTRY`, snapshots affected namespaces prior to mutation, prunes invalidated registrations on teardown, executes isolated per-entrypoint lifecycles (`preLaunch`, `main`, `client`, `modmenu`), and guarantees restoration of the `frozen` invariant inside deterministic `finally` guards.
- **DI & KeyMapping Hygiene**: Isolates entrypoint initialization failures per mod so a single broken entrypoint never aborts the transaction, purges orphaned `KeyMapping` entries from active `Options`, and synchronizes Fabric Loader's internal mod container map.

### 4. Execution Boundary & Render-Safe Resource Remounting
- **Transactional Rollback (`ReloadEngine`)**: Computes SHA-256 mod directory snapshots (`ModSnapshot` / `ModDiff`). On initialization failure or invariant violation, the transaction rolls back registry state and records full telemetry (`ReloadHistoryEntry`).
- **Safe Resource & Overlay Guard (`ResourceHotMount` / `ReloadBoundaryManager`)**: Remounts `PackRepository` and OpenGL shader pipelines only when no screen overlay (`LoadingOverlay`) holds the render pipeline, avoiding native access violations (`0xC0000005`). When invoked mid-session, `ReloadBoundaryManager` cleanly suspends the singleplayer level or multiplayer socket, executes the swap at the boundary, and restores the session.

---

## Execution Pipeline

Every call to `MutoApi.reloadAsync()` or `MutoApi.reloadWithBoundary(...)` proceeds through an ordered, auditable pipeline:

1. **Snapshot & Cryptographic Diffing** — Scans `/mods`, hashes archives via SHA-256, and computes `ModDiff (added, removed, updated, unchanged)`.
2. **Mixin Unweaving** — Identifies loaded target classes modified by removed/updated mods and triggers `retransformClasses()` to revert them to baseline bytecode.
3. **Module Teardown** — Closes mod resources, prunes registry namespaces, strips orphaned keybindings and ModMenu factories, removes stale containers from `FabricLoader`, and severs file locks via `JarUnlocker`.
4. **Knot Injection & Loader Sync** — Mounts incoming jars and JiJ dependencies onto `KnotClassLoader` and injects live `ModContainer` metadata into `FabricLoader`.
5. **Dynamic Mixin Weaving** — Registers incoming mixin configs and transforms target classes live in memory.
6. **Registry Thaw & Entrypoint Isolation** — Clears registry freeze flags, invokes isolated entrypoints, and restores freeze invariants.
7. **Pipeline Remount & Verification** — Synchronizes key mappings, reloads `PackRepository` / OpenGL shaders when safe, and emits `MutoEvents.RELOAD_FINISH`.

---

## Mod Classification Taxonomy

`ModScanner` partitions all discovered jars into deterministic execution tiers:

| Tier | Classification | Lifecycle Semantics |
|---|---|---|
| **Tier 1** | `CLEAN` | Client-only UI, HUD, and lightweight runtime tweaks with no persistent `main`/`server` registration. Swaps cleanly with minimal latency. |
| **Tier 2** | `STANDARD` | Full mods registering content, dynamic mixins, keybindings, or entrypoints. Executes complete bytecode retransformation, registry thawing, and namespace pruning. |
| **Tier 3** | `STUBBORN` | Core infrastructure locked to bootstrap/root classloaders (`minecraft`, `fabricloader`, `fabric-api`, `fabric-*`, `quilt*`, `muto`, `muto_menu`, `modmenu`, `java`). Protected from runtime unload; requires JVM restart if modified. |

---

## Developer Integration (`me.fy2ne.muto.api`)

Declare Muto Runtime as an optional compile-only dependency (`modCompileOnly`) so your mod operates standalone when Muto is absent and unlocks live hot-swapping when present.

### Gradle Configuration

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

### Programmatic Reload & Soft-Dependency Guard

```java
import net.fabricmc.loader.api.FabricLoader;
import me.fy2ne.muto.api.MutoApi;

public final class HotSwapBridge {
    public static void triggerSwap() {
        if (!FabricLoader.getInstance().isModLoaded("muto")) return;

        if (MutoApi.isSafeToReload()) {
            MutoApi.reloadAsync(stage -> System.out.println("[muto] " + stage))
                .thenAccept(result -> {
                    if (result.success()) {
                        System.out.println("Hot-swap completed in " + result.durationMs() + "ms");
                    } else {
                        System.err.println("Transaction rolled back: " + result.error());
                    }
                });
        }
    }
}
```

### Lifecycle Event Hooks

```java
import me.fy2ne.muto.api.MutoEvents;

MutoEvents.RELOAD_START.register((startMs, diff) -> {
    // Release external caches or invalidate derived registries prior to swap
});

MutoEvents.RELOAD_FINISH.register((endMs, diff, success, durationMs, error) -> {
    // Rebind UI views and refresh cached metadata after completion or rollback
});
```

---

## Public API Contract

| Symbol | Return Type | Specification |
|---|---|---|
| `MutoApi.isAvailable()` | `boolean` | Verifies runtime engine initialization |
| `MutoApi.isReloading()` | `boolean` | Indicates an active hot-swap transaction in flight |
| `MutoApi.isSafeToReload()` | `boolean` | Verifies client is at a title/boundary state (`level == null`) |
| `MutoApi.plan()` | `ReloadPlan` | Performs non-mutating SHA-256 scan and dependency diff |
| `MutoApi.reloadAsync()` | `CompletableFuture<ReloadResult>` | Executes the full asynchronous hot-swap pipeline |
| `MutoApi.reloadAsync(Consumer<String>)` | `CompletableFuture<ReloadResult>` | Executes reload with per-stage telemetry callbacks |
| `MutoApi.reloadWithBoundary(Screen)` | `void` | Gracefully exits active world/server, reloads, and reconnects |
| `MutoApi.getHistory()` | `List<ReloadHistoryEntry>` | Retrieves audited transaction history and diff logs |
| `MutoApi.getReloadableModIds()` | `Set<String>` | Returns IDs classified as dynamically reloadable (`CLEAN` / `STANDARD`) |
| `MutoApi.getStubbornModIds()` | `Set<String>` | Returns protected core mod IDs requiring JVM restart |

---

## Environment, Constraints & License

- **Minecraft**: Java Edition `>=26.1 <27.0` (verified on `26.3`)
- **Loader**: Fabric Loader `>=0.16.0` (built against `0.19.5`)
- **Fabric API**: `>=0.144.1` (built against `0.161.0+26.3`)
- **Runtime**: Java `25+` (standard HotSpot/OpenJDK supported; JetBrains Runtime / DCEVM optional)
- **Side**: Client-side only
- **Known Boundaries**: Mods that spawn unmanaged daemon threads without shutdown hooks, bind irreversible native libraries (e.g., raw graphics driver hooks), or enforce structural JVM schema changes (adding/removing fields or methods on already-loaded classes on standard HotSpot without DCEVM) remain subject to JVM class-redefinition limits.
- **License**: **GNU Lesser General Public License v3.0 (`LGPL-3.0-only`)** — proprietary and open-source mods may link against the Muto public API without inheriting copyleft; modifications to Muto Runtime itself remain open under LGPL-3.0.

---

## Note for End Users

Muto Runtime is a headless systems engine and adds no in-game screens on its own. Install it when required as a dependency by a compatible manager or interface (such as **Muto Menu**).
