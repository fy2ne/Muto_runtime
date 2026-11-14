export const RUNTIME_DOC_SECTIONS = [
  {
    id: "getting-started",
    title: "Getting Started",
    pages: [
      {
        slug: "overview",
        title: "Overview",
        tag: "Architecture",
        abstract:
          "Muto Runtime 0.1.0-beta introduces deterministic runtime mod loading, unloading, JVMTI bytecode retransformation, and registry thawing inside a live Fabric 26.x JVM.",
        body: `
Muto Runtime removes the full-process JVM restart cycle from Fabric modpacks. Drop, remove, enable, or disable mod JARs while Minecraft is running and let Muto synchronize classloaders, Mixin bytecode weaves, registries, and entrypoints in place.

> [!IMPORTANT]
> Muto Runtime targets **Minecraft 26.1 – 26.3** (\`26.x\`) running on **Java 25** and **Fabric Loader >= 0.16.0** with **Fabric API >= 0.144.1**.

## Four Coordinated Subsystems

Instead of relying on superficial reload wrappers, Muto Runtime coordinates four low-level JVM mechanisms:

1. **JVMTI Bytecode Retransformation** (\`MutoAgentBootstrap\`, \`DynamicMixinEngine\`, \`MutoClassFileTransformer\`): Dynamically attaches \`java.lang.instrument.Instrumentation\` and weaves or cleanly restores vanilla/target classes using ASM \`ClassWriter\` recomputation.
2. **ClassLoader Isolation & Windows Unlocker** (\`KnotInjector\`, \`JarUnlocker\`, \`MutoClassLoader\`): Injects URLs directly into \`KnotClassLoader\`, neutralizes Windows \`URLJarFile\` and \`ZipFile\` locks so hot-removed JARs can be deleted or renamed immediately, and drops stale loader caches.
3. **Registry Thawing & DI Guards** (\`RegistryFreezer\`, \`DiContainerGuard\`): Unfreezes \`MappedRegistry\`, clears holder caches and intrusive reference maps, unregisters custom network payload codecs and keybindings, and safely skips invalid dependency-injected entrypoints during teardown.
4. **Boundary Orchestration** (\`ReloadEngine\`, \`ReloadBoundaryManager\`, \`MutoReconnectingScreen\`): Automatically disconnects singleplayer or multiplayer sessions cleanly before swapping Mixin-tier mods and reconnects automatically afterward.

## Companion Mod

For players and modpack authors who want native in-game UI toggles, ModMenu cards, dependency safety warnings, and folder watching, install **[Muto Menu](https://mutomenu.fy2ne.me)** alongside Muto Runtime.
`,
      },
      {
        slug: "installation",
        title: "Installation & Setup",
        tag: "Setup",
        abstract:
          "Drop the runtime JAR into mods/ for end users, or add Muto Runtime as a Gradle compileOnly dependency for downstream Fabric integrations.",
        body: `
## For Players & Modpack Authors

1. Ensure your instance runs **Minecraft 26.1 – 26.3** (\`26.x\`) with **Java 25**.
2. Install **Fabric Loader >= 0.16.0** and **Fabric API >= 0.144.1**.
3. Place \`muto-0.1.0-beta+26.x.jar\` into your \`.minecraft/mods\` directory.
4. Optionally add **Muto Menu** (\`mutomenu.fy2ne.me\`) for in-game ModMenu toggles, hot-swap history, and automatic folder watching.

> [!NOTE]
> Muto Runtime initializes \`MutoAgentBootstrap\` automatically at mod pre-launch or boot. You do not need to manually pass \`-javaagent\` JVM flags on standard JDK/JRE 25 setups, although explicit agent flags are supported if your launcher disables dynamic self-attach.

## For Mod Developers (Gradle)

Reference Muto Runtime via Maven Local or GitHub repository artifacts without shading it into your own JAR:

\`\`\`groovy
repositories {
    mavenLocal()
}

dependencies {
    compileOnly "me.fy2ne.muto:muto:0.1.0-beta+26.x"
}
\`\`\`

Declare an optional or required dependency in your \`fabric.mod.json\`:

\`\`\`json
{
  "suggests": {
    "muto": "~0.1.0-beta"
  }
}
\`\`\`

Guard runtime calls safely with \`MutoApi.isAvailable()\` or optional Fabric entrypoint checks when Muto is only a soft dependency.
`,
      },
    ],
  },
  {
    id: "subsystems",
    title: "Core Architecture",
    pages: [
      {
        slug: "jvmti-mixins",
        title: "JVMTI & Dynamic Mixins",
        tag: "Instrumentation",
        abstract:
          "How Muto Runtime attaches Instrumentation, tracks original pristine class bytes, and calls retransformClasses() to apply or strip Mixin bytecode in a live JVM.",
        body: `
Historically, SpongePowered Mixin only transformed target classes during initial startup classloading inside Knot. Once a class like \`LevelRenderer\` or \`Minecraft\` was defined in the JVM, newly dropped mods could never inject their mixins and removed mods left dangling bytecode behind.

Muto Runtime solves this at the JVMTI layer inside \`me.fy2ne.muto.engine.agent\` and \`me.fy2ne.muto.engine.mixin\`.

## Agent Attachment (\`MutoAgentBootstrap\`)

- Resolves \`java.lang.instrument.Instrumentation\` on startup via the active ByteBuddy/agent bootstrapper.
- Registers a persistent \`MutoClassFileTransformer\` marked \`canRetransform = true\`.
- Stores clean baseline classfile buffers when target classes are first intercepted so unload operations can roll bytecode back cleanly.

## Live Retransformation (\`DynamicMixinEngine\`)

1. **Snapshot Scan**: Parses \`fabric.mod.json\` and declared \`*.mixins.json\` configs inside newly loaded or unloaded mod JARs.
2. **Bytecode Weaving / Restoration**: Resolves affected vanilla or mod target classes and schedules \`Instrumentation.retransformClasses(Class<?>...)\`.
3. **Frame Computation**: Uses ASM frame recomputation safely under Java 25 classfile constraints without triggering \`VerifyError\` or corrupting running call stacks.

> [!WARNING]
> Structural changes forbidden by the JVM specification on already-loaded classes (adding/removing instance fields or altering class hierarchy) cannot be applied via standard \`retransformClasses()\` and automatically elevate the mod to Boundary/Stubborn validation in \`ReloadPlan\`.
`,
      },
      {
        slug: "knot-jarunlocker",
        title: "Knot ClassLoader & JarUnlocker",
        tag: "ClassLoading",
        abstract:
          "How KnotInjector mutates URLClassPath paths inside Fabric's KnotClassLoader while JarUnlocker releases Windows file descriptor locks on hot-removed JARs.",
        body: `
On Windows, \`java.net.URLClassLoader\` and \`JarURLConnection\` retain open native file handles in \`URLJarFile\` caches long after Java code stops reading them. Attempting to rename, overwrite, or delete an unloaded mod JAR normally fails with \`FileSystemException\`.

## Windows File Lock Release (\`JarUnlocker\`)

- Scans \`sun.net.www.protocol.jar.JarFileFactory\` internal cache maps (\`fileCache\` and \`urlCache\`).
- Matches canonical URIs pointing to unloaded or disabled mod JARs, closes underlying \`JarFile\` descriptors deterministically, and evicts stale URL entries before disk renames execute.
- Walks active URL loaders cleanly so \`mods/example.jar\` can transition to \`mods/example.jar.disabled\` on the very same tick without waiting for garbage collection.

## Knot Graph Mutation (\`KnotInjector\`)

- Adds valid JAR URLs into \`KnotClassLoader\` when a new mod activates.
- Registers mod containers and entrypoint definitions inside \`FabricLoaderImpl\` so downstream calls like \`FabricLoader.getInstance().isModLoaded(modId)\` stay truthful immediately after swap completion.
`,
      },
      {
        slug: "registry-thawing",
        title: "Registry Thawing & DI Guards",
        tag: "State Synchronization",
        abstract:
          "Bypassing Fabric registry immutability via MappedRegistryAccessor, pruning network payload codecs and keybindings, and protecting Guice/DI containers on teardown.",
        body: `
After Fabric completes initial bootstrap, vanilla registries freeze via \`MappedRegistry.frozen = true\`. Simply invoking a hot-loaded mod entrypoint crashes immediately with \`IllegalStateException: Registry is already frozen\`.

## Atomic Registry Unfreeze (\`RegistryFreezer\`)

Using \`MappedRegistryAccessor\` mixins and transactional state guards, \`RegistryFreezer\`:

1. Temporarily unlocks target registries (\`BUILT_IN_REGISTRIES\`) during the mutation window.
2. Records or prunes dynamically registered \`Item\`, \`Block\`, \`EntityType\`, \`SoundEvent\`, and codec entries belonging to swapped mod namespaces.
3. Re-seals registries cleanly once entrypoints or teardown hooks finish, rebuilding frozen intrusive-holder invariants so chunk meshing and creative tabs remain consistent.

## Networking & KeyMapping Cleanup

- \`PayloadTypeRegistryImplMixin\` & \`CustomPacketPayloadMixin\` unregister custom \`CustomPacketPayload\` codecs registered by unloaded mods so servers and clients never stall on orphaned packet IDs.
- \`GlobalReceiverRegistryMixin\` drops stale play/configuration receivers.
- \`KeyMappingRegistryImplMixin\` & \`OptionsAccessor\` remove unloaded mod keybindings from vanilla \`Options\` cleanly.

## DI Container Guards (\`DiContainerGuard\`)

Many library-heavy mods rely on Guice or reflection-based DI containers during teardown or re-initialization. \`DiContainerGuard\` isolates entrypoint disposal and shields the swap pipeline against missing class references when dependent services tear down out of strict topological order.
`,
      },
      {
        slug: "boundary-tiers",
        title: "Mod Tiers & Boundary Reload",
        tag: "Safety",
        abstract:
          "How ModTier classifies every mod into Reloadable, Mixin, or Stubborn tiers and orchestrates disconnect-swap-rejoin across live sessions.",
        body: `
Not every mod can be hot-swapped mid-frame while a player is rendering chunks in a world. \`ModDependencyAnalyzer\` and \`ReloadEngine\` inspect every JAR into an explicit tier inside \`ModTier\`:

| Tier | Description | Execution Strategy |
| :--- | :--- | :--- |
| **Reloadable** | Pure entrypoints, commands, keybinds, HUD/client tweaks, resources | Swapped asynchronously or in-place; optional resource mount refresh via \`ResourceHotMount\` |
| **Mixin Tier** | Declares active \`*.mixins.json\` configs or bytecode injections | Triggers \`DynamicMixinEngine\` + \`Instrumentation.retransformClasses()\` |
| **Stubborn / Core** | Early ASM pre-launch hooks, shader pipelines (e.g. Iris), core render backends | Flagged in \`MutoApi.getStubbornModIds()\` and isolated with rollback guards |
| **Protected** | Self-protection for \`muto\`, \`fabricloader\`, \`fabric-api\`, \`minecraft\`, \`java\` | Excluded from unsafe runtime teardown |

## Boundary Execution (\`ReloadBoundaryManager\`)

If the player invokes \`MutoApi.reloadWithBoundary(screen)\` while inside a singleplayer world or connected to a multiplayer server:

1. Muto snapshots whether the connection was an integrated server (world folder name) or a remote \`ServerData\` address.
2. Cleanly tears down the client world to \`MutoReconnectingScreen\` so active chunk threads and entity renderers park safely.
3. Executes \`ReloadEngine.INSTANCE.reloadAsync(...)\` against the quiesced client state.
4. Automatically rejoins the exact singleplayer world or multiplayer server once swap verification passes.
`,
      },
    ],
  },
  {
    id: "developer-api",
    title: "Developer API",
    pages: [
      {
        slug: "muto-api",
        title: "MutoApi Reference",
        tag: "Java API",
        abstract:
          "Complete public surface of me.fy2ne.muto.api.MutoApi: loading single JARs, unloading mod IDs, computing plans, and triggering boundary reloads.",
        body: `
All programmatic orchestration passes through \`me.fy2ne.muto.api.MutoApi\`. Always verify \`MutoApi.isAvailable()\` if your mod treats Muto as an optional dependency.

## Planning & Availability

\`\`\`java
if (MutoApi.isAvailable() && !MutoApi.isReloading()) {
    ReloadPlan plan = MutoApi.plan();
    Set<String> reloadable = MutoApi.getReloadableModIds();
    Set<String> stubborn = MutoApi.getStubbornModIds();
}
\`\`\`

## Loading or Unloading a Specific Mod

\`\`\`java
// Load a JAR dynamically into the live JVM
MutoApi.loadMod(Path.of("mods/example-mod.jar"), stage -> {
    LOGGER.info("[Muto Stage] {}", stage);
}).thenAccept(result -> {
    LOGGER.info("Swap finished: {}", result.success());
});

// Unload a loaded mod by id
MutoApi.unloadMod("examplemod").thenAccept(result -> {
    LOGGER.info("Unloaded cleanly: {}", result.success());
});
\`\`\`

## Boundary Reload from Inside a World

\`\`\`java
// Safe to call while connected to SP or MP — shows progress & rejoins automatically
Minecraft.getInstance().execute(() -> {
    MutoApi.reloadWithBoundary(Minecraft.getInstance().screen);
});
\`\`\`

## Method Summary

| Signature | Return Type | Purpose |
| :--- | :--- | :--- |
| \`isAvailable()\` | \`boolean\` | Checks if \`muto\` runtime is active in FabricLoader |
| \`isReloading()\` | \`boolean\` | True while a swap transaction holds the engine lock |
| \`isSafeToReload()\` | \`boolean\` | True when \`mc.level == null\` and no integrated server runs |
| \`plan()\` | \`ReloadPlan\` | Computes added, removed, and updated JAR diffs without mutating |
| \`reloadAsync()\` | \`CompletableFuture<ReloadResult>\` | Executes a full diff-based hot swap asynchronously |
| \`loadMod(Path)\` | \`CompletableFuture<ReloadResult>\` | Injects a single mod JAR into Knot & initializes entrypoints |
| \`unloadMod(String)\` | \`CompletableFuture<ReloadResult>\` | Tears down a mod, strips mixins, and releases file locks |
| \`reloadWithBoundary(Screen)\` | \`void\` | Disconnects safely if in-world, swaps, then rejoins automatically |
| \`getHistory()\` | \`List<ReloadHistoryEntry>\` | Returns timestamped audit log of prior reload transactions |
| \`isAutoReloadResources()\` | \`boolean\` | Reports whether vanilla resource packs refresh post-swap |
| \`setAutoReloadResources(boolean)\` | \`void\` | Configures automatic client asset reload after swap completion |
`,
      },
      {
        slug: "muto-events",
        title: "Lifecycle Events",
        tag: "Events",
        abstract:
          "Subscribe to MutoEvents.RELOAD_START and RELOAD_FINISH to invalidate caches or rebuild custom UI lists before and after mod swaps.",
        body: `
Downstream mods and UI layers like **Muto Menu** stay synchronized with Muto Runtime via \`me.fy2ne.muto.api.MutoEvents\`.

## Subscribing to Events

\`\`\`java
import me.fy2ne.muto.api.MutoEvents;
import net.minecraft.client.Minecraft;

public void registerHooks() {
    MutoEvents.RELOAD_START.register((startMs, expectedDiff) -> {
        MyRenderCache.invalidateAll();
    });

    MutoEvents.RELOAD_FINISH.register((endMs, diff, success, durationMs, error) -> {
        if (!success) {
            LOGGER.warn("Muto rolled back swap after {}ms: {}", durationMs, error);
            return;
        }
        Minecraft.getInstance().execute(MyModScreen::rebuildCards);
    });
}
\`\`\`

> [!CAUTION]
> Both \`RELOAD_START\` and \`RELOAD_FINISH\` fire synchronously on the background reload worker thread, **not** the main Minecraft client thread. Any OpenGL texture bind, screen refresh, or widget rebuild MUST be dispatched via \`Minecraft.getInstance().execute(...)\`.

## Threading & No-Op Guarantees

- \`RELOAD_FINISH\` is guaranteed to invoke even when a planned reload resolves as a no-op or throws and rolls back cleanly, passing the caught \`Throwable\` in \`error\`.
- \`ModDiff\` exposes exact lists of added, removed, and updated mods processed inside the transaction.
`,
      },
    ],
  },
  {
    id: "project",
    title: "Project",
    pages: [
      {
        slug: "changelog",
        title: "Changelog (0.1.0-beta)",
        tag: "Release",
        abstract:
          "Initial scientific beta release 0.1.0-beta+26.x introducing live JVMTI Mixin retransformation, Win32 JarUnlocker, registry thawing, and boundary rejoin.",
        body: `
## v0.1.0-beta+26.x — Initial Beta Release

First public beta release of Muto Runtime for Minecraft \`26.1 – 26.3\` (Java 25, Fabric Loader \`>=0.16.0\`, Fabric API \`>=0.144.1\`).

### Core Runtime & JVM Instrumentation
- **JVMTI Bytecode Retransformation Engine**: Implemented \`MutoAgentBootstrap\`, \`DynamicMixinEngine\`, and \`MutoClassFileTransformer\` to dynamically re-transform loaded target classes using \`Instrumentation.retransformClasses()\`.
- **Knot ClassLoader & Windows JarUnlocker**: Implemented \`KnotInjector\` and \`JarUnlocker\` to inject URLs into \`KnotClassLoader\` at runtime and close stubborn Windows \`URLJarFile\` locks on teardown.
- **Registry Thawing & State Sync**: Implemented \`RegistryFreezer\` (\`MappedRegistryAccessor\`), \`DiContainerGuard\`, and Mixin cleanups for \`PayloadTypeRegistryImpl\`, \`GlobalReceiverRegistry\`, and \`KeyMappingRegistryImpl\`.
- **Resource Hot-Mounting**: Added \`ResourceHotMount\` so newly loaded or unloaded mods update client resource packs cleanly.

### Boundary & API Stability
- **World Boundary Handoff**: Added \`ReloadBoundaryManager\` and \`MutoReconnectingScreen\` for deterministic disconnect -> swap -> automatic rejoin across singleplayer and multiplayer.
- **Public SDK**: Stabilized \`MutoApi\` (\`loadMod\`, \`unloadMod\`, \`reloadAsync\`, \`reloadWithBoundary\`, \`plan\`, tier queries) and \`MutoEvents\` (\`RELOAD_START\`, \`RELOAD_FINISH\`).
`,
      },
      {
        slug: "license-faq",
        title: "License & FAQ",
        tag: "Legal",
        abstract:
          "Elastic License 2.0 (ELv2) licensing summary, diagnostics APIs, and common troubleshooting answers.",
        body: `
## License

Muto Runtime is distributed under the **Elastic License 2.0 (ELv2)**. Source code is inspectable on [GitHub](https://github.com/fy2ne/Muto_runtime), modpacks and developers may integrate against \`MutoApi\`, and redistributions must respect ELv2 terms.

## Diagnostics & Simulation Mode

Muto Runtime ships an internal diagnostics & dry-run simulator used by Muto Menu's \`MutoDevScreen\`:

- \`MutoApi.setDevMode(true)\` streams structured per-stage swap diagnostics into \`muto-dev.log\`.
- \`MutoApi.runReloadSimulation()\` validates active JAR dependencies, Mixin targets, and tier assignments without touching loaded registries.

## FAQ

### Why do some core mods still flag as Stubborn Tier?
Mods that rewrite early JVM bootstrap classes before Knot finishes classloading — or initialize deep external native/shader pipelines tied to OS graphics contexts — cannot safely dismantle those OS handles inside \`retransformClasses()\`. \`ModDependencyAnalyzer\` reports them explicitly via \`getStubbornModIds()\`.

### Where is the in-game toggle UI?
Muto Runtime intentionally separates the low-level JVM orchestration engine from Minecraft GUI screens. Install **[Muto Menu](https://mutomenu.fy2ne.me)** for ModMenu integration, cards, dependency alerts, and folder watching.
`,
      },
    ],
  },
];
