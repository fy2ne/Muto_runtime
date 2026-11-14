import { useState } from "react";
import { Link } from "react-router-dom";
import {
  ArrowRight,
  ArrowUpRight,
  ChevronRight,
  Cpu,
  Gauge,
  Layers,
  Lock,
  RefreshCw,
  ShieldCheck,
  Terminal,
} from "lucide-react";
import { SkyCanvas } from "../components/SkyCanvas";
import { FrameCard } from "../components/FrameCard";
import { CodeBlock } from "../components/CodeBlock";

const PIPELINE_MODES = {
  pipeline: {
    eyebrow: "Hot-Swap Execution Profile",
    headline: "In-process JVM mutation",
    accentText: "Zero process restart.",
    note: "Verified across Fabric Loader >=0.16.0 on Minecraft 26.x / Java 25.",
    rows: [
      { label: "1. Snapshot & JarUnlocker handle release", tag: "Lock-Free I/O", fill: 88, active: true },
      { label: "2. JVMTI retransformClasses() Mixin weave", tag: "ASM Verified", fill: 94, active: true },
      { label: "3. RegistryFreezer unfreeze & codec prune", tag: "Transactional", fill: 91, active: true },
      { label: "4. Cold JVM Restart (baseline)", tag: "Full Reboot", fill: 24, active: false },
    ],
  },
  tiers: {
    eyebrow: "Classification Matrix",
    headline: "Tier-aware safety orchestration",
    accentText: "Deterministic fallback.",
    note: "ModDependencyAnalyzer inspects entrypoints, mixins.json, and core hooks before commit.",
    rows: [
      { label: "Tier 0 · Reloadable (entrypoints, commands, HUD)", tag: "Async In-Place", fill: 96, active: true },
      { label: "Tier 1 · Mixin Tier (DynamicMixinEngine retransform)", tag: "JVMTI Live", fill: 86, active: true },
      { label: "World Boundary (ReloadBoundaryManager + Rejoin)", tag: "SP & MP Auto", fill: 90, active: true },
      { label: "Protected Core (muto, fabricloader, fabric-api)", tag: "Locked Safe", fill: 100, active: false },
    ],
  },
};

const CODE_TABS = [
  {
    id: "gradle",
    label: "build.gradle",
    lang: "groovy",
    code: `repositories {
    mavenLocal()
}

dependencies {
    compileOnly "me.fy2ne.muto:muto:0.1.0-beta+26.x"
}`,
  },
  {
    id: "api",
    label: "MutoApi.java",
    lang: "java",
    code: `if (MutoApi.isAvailable() && !MutoApi.isReloading()) {
    // Load a mod JAR directly into the live KnotClassLoader
    MutoApi.loadMod(Path.of("mods/example-mod.jar"), stage -> {
        LOGGER.info("stage -> {}", stage);
    });

    // Or safely trigger disconnect -> swap -> rejoin in-world
    MutoApi.reloadWithBoundary(Minecraft.getInstance().screen);
}`,
  },
  {
    id: "events",
    label: "MutoEvents.java",
    lang: "java",
    code: `MutoEvents.RELOAD_START.register((startMs, expectedDiff) -> {
    MyRenderCache.invalidateAll();
});

MutoEvents.RELOAD_FINISH.register((endMs, diff, ok, ms, err) -> {
    if (ok) {
        Minecraft.getInstance().execute(MyModList::rebuild);
    }
});`,
  },
  {
    id: "modjson",
    label: "fabric.mod.json",
    lang: "json",
    code: `{
  "schemaVersion": 1,
  "id": "my-addon",
  "depends": {
    "fabricloader": ">=0.16.0",
    "fabric-api": ">=0.144.1",
    "minecraft": "~26.1"
  },
  "suggests": {
    "muto": "~0.1.0-beta"
  }
}`,
  },
];

const SUBSYSTEM_CELLS = [
  {
    icon: Cpu,
    title: "JVMTI Bytecode Retransformation",
    desc: "MutoAgentBootstrap attaches java.lang.instrument.Instrumentation live so DynamicMixinEngine can apply or strip Mixin weaves via retransformClasses() without restarting.",
  },
  {
    icon: Lock,
    title: "Win32 JarUnlocker & KnotInjector",
    desc: "Deterministic URLJarFile and ZipFile descriptor release clears OS file locks immediately while KnotInjector synchronizes FabricLoader containers on disk.",
  },
  {
    icon: Layers,
    title: "Atomic Registry Thawing",
    desc: "RegistryFreezer temporarily unlocks MappedRegistry instances via MappedRegistryAccessor, registers or prunes namespaced holders, and reseals invariants atomically.",
  },
  {
    icon: ShieldCheck,
    title: "DI Container & Codec Guards",
    desc: "DiContainerGuard isolates invalid dependency-injection teardown while network mixins clean up CustomPacketPayload codecs, global receivers, and KeyMappings.",
  },
  {
    icon: RefreshCw,
    title: "In-World Boundary Rejoin",
    desc: "ReloadBoundaryManager snapshots your active singleplayer world or multiplayer server, parks the game on MutoReconnectingScreen, swaps safely, and reconnects.",
  },
  {
    icon: Terminal,
    title: "Transactional Rollback & DevMode",
    desc: "Every swap captures a pre-flight ModSnapshot with dry-run simulation via MutoApi.runReloadSimulation() and structured trace logs in muto-dev.log.",
  },
];

const MARQUEE_TAGS = [
  "JVMTI RETRANSFORM",
  "KNOT CLASSLOADER",
  "WIN32 JAR UNLOCKER",
  "MAPPED REGISTRY THAW",
  "DYNAMIC MIXIN ENGINE",
  "DI CONTAINER GUARD",
  "RESOURCE HOT MOUNT",
  "BOUNDARY AUTO-REJOIN",
  "JAVA 25 // FABRIC 26.X",
];

const FAQS = [
  {
    q: "Does Muto Runtime actually load and unload Mixin mods inside a running JVM?",
    a: "Yes. Starting in 0.1.0-beta, MutoAgentBootstrap registers a retransform-capable ClassFileTransformer (MutoClassFileTransformer) paired with DynamicMixinEngine. When a mod is added or removed, clean baseline class bytes are re-woven or restored through Instrumentation.retransformClasses().",
  },
  {
    q: "Why does Windows normally block hot-removing JARs, and how does JarUnlocker fix it?",
    a: "Java's URLClassLoader and JarURLConnection keep open native file handles in JarFileFactory caches on Windows even after classloaders stop reading. JarUnlocker locates matching canonical URLs, closes underlying JarFile streams, and evicts cache entries before file operations execute.",
  },
  {
    q: "What happens if I swap mods while inside a singleplayer world or multiplayer server?",
    a: "Calling MutoApi.reloadWithBoundary(screen) safely disconnects the local client to MutoReconnectingScreen, waits for integrated server/render threads to park cleanly, commits the hot-swap, and automatically rejoins your exact world or server.",
  },
  {
    q: "What is the difference between Muto Runtime and Muto Menu?",
    a: "Muto Runtime (muto.fy2ne.me) is the headless JVM orchestration engine, classloader mutator, and Java developer SDK. Muto Menu (mutomenu.fy2ne.me) is the companion client UI that integrates into ModMenu with visual toggles, dependency warnings, folder auto-watching, and history screens.",
  },
];

export function RuntimeLanding() {
  const [heroTab, setHeroTab] = useState("pipeline");
  const [codeTab, setCodeTab] = useState("api");

  const activeHero = PIPELINE_MODES[heroTab];
  const activeSnippet = CODE_TABS.find((t) => t.id === codeTab) || CODE_TABS[1];

  return (
    <main className="relative flex-1 overflow-x-clip">
      {/* SECTION 1: ATMOSPHERIC CRIMSON SKY HERO (Weavetab /mcp pattern) */}
      <section className="hero-sky relative -mt-14 overflow-hidden pt-14">
        <SkyCanvas rgb={[229, 44, 41]} warmRgb={[255, 118, 92]} />

        <div className="relative z-10 mx-auto max-w-[1280px] px-5 pb-24 pt-16 sm:px-8 sm:pb-32 sm:pt-24 lg:px-10">
          <div className="grid grid-cols-1 items-center gap-12 lg:grid-cols-12">
            <div className="space-y-6 lg:col-span-6">
              <div className="unified-eyebrow">
                <span className="unified-eyebrow-dot" />
                <span>Live JVM Mod Orchestration // Fabric</span>
              </div>

              <h1 className="text-balance font-serif text-4xl font-normal leading-[1.04] tracking-[-0.025em] text-[var(--color-fg-default)] sm:text-5xl lg:text-[4.15rem]">
                Hot-swap Fabric mods inside a live JVM
              </h1>

              <p className="max-w-xl text-base leading-relaxed text-[var(--color-fg-muted)] sm:text-lg">
                Real-time <span className="font-mono text-sm text-[var(--color-fg-default)]">Instrumentation.retransformClasses()</span>, Windows{" "}
                <span className="font-mono text-sm text-[var(--color-fg-default)]">JarUnlocker</span> handle release, and atomic registry thawing — built so you stop restarting Minecraft to iterate or test mods.
              </p>

              <div className="flex flex-wrap items-center gap-4 pt-1">
                <Link
                  to="/docs/overview"
                  className="inline-flex items-center gap-2 rounded-lg bg-[var(--color-brand-default)] px-4 py-2.5 text-sm font-medium text-white no-underline transition-colors hover:bg-[var(--color-brand-hover)]"
                >
                  <span>Read Documentation</span>
                  <ArrowRight className="size-4" />
                </Link>

                <a
                  href="https://github.com/fy2ne/Muto_runtime"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="word-link text-sm font-medium"
                >
                  <span>fy2ne/Muto_runtime</span>
                  <ArrowUpRight className="size-3.5" />
                </a>

                <a
                  href="https://mutomenu.fy2ne.me"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="word-link text-sm font-medium"
                >
                  <span>Companion: Muto Menu</span>
                  <ArrowUpRight className="size-3.5" />
                </a>
              </div>
            </div>

            {/* HERO TELEMETRY CARD */}
            <div className="lg:col-span-6">
              <div className="hero-glass rounded-2xl p-6 shadow-[var(--shadow-card)] sm:p-7">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <p className="m-0 font-mono text-[11px] uppercase tracking-[0.1em] text-[var(--color-fg-muted)]">
                      {activeHero.eyebrow}
                    </p>
                    <h2 className="mt-2 font-sans text-xl font-semibold tracking-[-0.02em] text-[var(--color-fg-default)] sm:text-2xl">
                      {activeHero.headline}{" "}
                      <span className="text-[var(--color-brand-default)]">{activeHero.accentText}</span>
                    </h2>
                  </div>

                  <div className="inline-flex items-center gap-1 rounded-lg border border-[var(--color-border-default)] bg-[var(--color-surface-elevated)] p-1">
                    <button
                      type="button"
                      onClick={() => setHeroTab("pipeline")}
                      className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 font-mono text-[11px] transition-colors cursor-pointer ${
                        heroTab === "pipeline"
                          ? "bg-[var(--color-brand-default)] text-white"
                          : "text-[var(--color-fg-muted)] hover:text-[var(--color-fg-default)]"
                      }`}
                    >
                      <Gauge className="size-3" />
                      <span>Pipeline</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setHeroTab("tiers")}
                      className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 font-mono text-[11px] transition-colors cursor-pointer ${
                        heroTab === "tiers"
                          ? "bg-[var(--color-brand-default)] text-white"
                          : "text-[var(--color-fg-muted)] hover:text-[var(--color-fg-default)]"
                      }`}
                    >
                      <Layers className="size-3" />
                      <span>Tiers</span>
                    </button>
                  </div>
                </div>

                <div className="mt-6 space-y-5">
                  {activeHero.rows.map((row) => (
                    <div key={row.label}>
                      <div className="flex items-baseline justify-between gap-3">
                        <span
                          className={`text-[13.5px] ${
                            row.active
                              ? "font-medium text-[var(--color-fg-default)]"
                              : "text-[var(--color-fg-muted)]"
                          }`}
                        >
                          {row.label}
                        </span>
                        <span className="shrink-0 font-mono text-[11px] text-[var(--color-fg-subtle)]">
                          {row.tag}
                        </span>
                      </div>
                      <div className="relative mt-2 h-2 overflow-hidden rounded-[2px] bg-[var(--color-fg-default)]/[0.08]">
                        <div
                          className="stat-bar h-full rounded-[2px]"
                          style={{
                            "--fill": `${row.fill}%`,
                            backgroundColor: row.active
                              ? "var(--color-brand-default)"
                              : "rgba(161, 161, 170, 0.35)",
                          }}
                        />
                      </div>
                    </div>
                  ))}
                </div>

                <div className="mt-6 flex items-center justify-between gap-4 border-t border-[var(--color-border-default)] pt-4 text-xs text-[var(--color-fg-subtle)]">
                  <span>{activeHero.note}</span>
                  <Link to="/docs/jvmti-mixins" className="word-link shrink-0 font-medium">
                    Architecture <ArrowUpRight className="size-3" />
                  </Link>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* MARQUEE ENGINE BAR */}
      <section className="relative w-full overflow-hidden border-y border-[var(--color-border-default)] bg-[var(--color-canvas-subtle)] py-4">
        <div className="pointer-events-none absolute inset-y-0 left-0 z-10 w-24 bg-gradient-to-r from-[var(--color-canvas-subtle)] to-transparent" />
        <div className="pointer-events-none absolute inset-y-0 right-0 z-10 w-24 bg-gradient-to-l from-[var(--color-canvas-subtle)] to-transparent" />
        <div className="animate-marquee items-center gap-12 whitespace-nowrap">
          {[...MARQUEE_TAGS, ...MARQUEE_TAGS, ...MARQUEE_TAGS].map((tag, idx) => (
            <span
              key={idx}
              className="inline-flex items-center gap-2.5 font-mono text-[11px] uppercase tracking-[0.14em] text-[var(--color-fg-muted)]"
            >
              <span className="h-1.5 w-1.5 rounded-full bg-[var(--color-brand-default)]" />
              {tag}
            </span>
          ))}
        </div>
      </section>

      {/* SECTION 2: DEVELOPER INTEGRATION / CODE SWITCHER */}
      <section className="mx-auto max-w-[1280px] px-5 py-24 sm:px-8 sm:py-28 lg:px-10">
        <div className="grid grid-cols-1 items-center gap-12 lg:grid-cols-12">
          <div className="space-y-5 lg:col-span-5">
            <div className="unified-eyebrow">
              <span className="unified-eyebrow-dot" />
              <span>Java Developer Contract</span>
            </div>

            <h2 className="text-balance font-serif text-3xl font-normal leading-[1.1] tracking-[-0.02em] text-[var(--color-fg-default)] sm:text-4xl lg:text-[2.75rem]">
              One public API for dynamic mod orchestration
            </h2>

            <p className="text-[15.5px] leading-relaxed text-[var(--color-fg-muted)]">
              Integrate cleanly against{" "}
              <Link to="/docs/muto-api" className="word-link font-medium">
                MutoApi
              </Link>{" "}
              and subscribe to{" "}
              <Link to="/docs/muto-events" className="word-link font-medium">
                MutoEvents
              </Link>{" "}
              to drop JARs in place, inspect Mixin vs Reloadable tiers, and flush custom client caches on swap.
            </p>

            <div className="flex flex-wrap items-center gap-5 pt-2">
              <Link
                to="/docs/installation"
                className="inline-flex items-center gap-1.5 rounded-lg border border-[var(--color-border-strong)] bg-[var(--color-surface-elevated)] px-4 py-2 text-sm font-medium text-[var(--color-fg-default)] no-underline transition-colors hover:bg-[var(--color-surface-hover)]"
              >
                <span>Gradle Setup</span>
                <ChevronRight className="size-4 text-[var(--color-brand-default)]" />
              </Link>

              <Link
                to="/docs/muto-api"
                className="group inline-flex items-center gap-1 text-sm font-medium text-[var(--color-fg-muted)] no-underline transition-colors hover:text-[var(--color-fg-default)]"
              >
                <span>Explore MutoApi</span>
                <ChevronRight className="size-3.5 transition-transform group-hover:translate-x-0.5" />
              </Link>
            </div>
          </div>

          <div className="lg:col-span-7">
            <FrameCard innerClassName="bg-[var(--color-surface-default)] p-5 sm:p-6">
              <div className="flex flex-wrap items-center justify-between gap-2 pb-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  {CODE_TABS.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => setCodeTab(t.id)}
                      className={`rounded-md px-2.5 py-1 font-mono text-[11.5px] transition-colors cursor-pointer ${
                        codeTab === t.id
                          ? "bg-[var(--color-brand-default)] text-white"
                          : "border border-[var(--color-border-default)] bg-transparent text-[var(--color-fg-muted)] hover:text-[var(--color-fg-default)]"
                      }`}
                    >
                      {t.label}
                    </button>
                  ))}
                </div>
              </div>
              <CodeBlock
                code={activeSnippet.code}
                lang={activeSnippet.lang}
                filename={activeSnippet.label}
              />
            </FrameCard>
          </div>
        </div>
      </section>

      {/* SECTION 3: 6-CELL BORDERED SUBSYSTEM GRID (Weavetab /mcp pattern) */}
      <section className="border-t border-[var(--color-border-default)] py-24">
        <div className="mx-auto max-w-[1280px] px-5 sm:px-8 lg:px-10">
          <div className="mx-auto mb-14 max-w-2xl text-center">
            <div className="unified-eyebrow mb-4 justify-center">
              <span className="unified-eyebrow-dot" />
              <span>Engine Subsystems</span>
            </div>
            <h2 className="text-balance font-serif text-3xl font-normal leading-[1.1] tracking-[-0.02em] text-[var(--color-fg-default)] sm:text-4xl lg:text-[2.85rem]">
              Engineered beneath Fabric Loader
            </h2>
            <p className="mt-4 text-[15.5px] leading-relaxed text-[var(--color-fg-muted)]">
              Every brittle link in a running JVM — locked JAR files, frozen registries, active Mixin targets, and packet codecs — handled explicitly.
            </p>
          </div>

          <div className="grid grid-cols-1 border-b border-t border-[var(--color-border-default)] md:grid-cols-2 lg:grid-cols-3">
            {SUBSYSTEM_CELLS.map((cell, idx) => {
              const Icon = cell.icon;
              const notLastCol = (idx + 1) % 3 !== 0;
              const notLastRow = idx < 3;
              return (
                <div
                  key={cell.title}
                  className={`p-8 sm:p-10 ${
                    notLastCol ? "lg:border-r lg:border-[var(--color-border-default)]" : ""
                  } ${notLastRow ? "border-b border-[var(--color-border-default)]" : ""}`}
                >
                  <div className="mb-4 inline-flex h-9 w-9 items-center justify-center rounded-lg border border-[var(--color-brand-default)]/30 bg-[var(--color-brand-soft)] text-[var(--color-brand-default)]">
                    <Icon className="size-4" />
                  </div>
                  <h3 className="m-0 font-sans text-[17px] font-semibold tracking-tight text-[var(--color-fg-default)]">
                    {cell.title}
                  </h3>
                  <p className="mt-2.5 m-0 text-sm leading-relaxed text-[var(--color-fg-muted)]">
                    {cell.desc}
                  </p>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* SECTION 4: FAQ FRAME (Weavetab Landing pattern) */}
      <section className="mx-auto max-w-[1280px] px-5 pb-24 sm:px-8 lg:px-10">
        <FrameCard>
          <div className="grid lg:grid-cols-[1fr_1.75fr]">
            <div className="border-b border-[var(--color-border-default)] p-8 sm:p-10 lg:border-b-0 lg:border-r lg:p-12">
              <div className="unified-eyebrow mb-4">
                <span className="unified-eyebrow-dot" />
                <span>Technical Questions</span>
              </div>
              <h2 className="m-0 font-serif text-3xl font-normal leading-[1.08] tracking-[-0.02em] text-[var(--color-fg-default)] sm:text-4xl">
                How it holds together
              </h2>
              <p className="mt-4 max-w-sm text-sm leading-relaxed text-[var(--color-fg-muted)]">
                Deep dives on bytecode safety, Windows file unlocking, and boundary reloads are documented end-to-end in the runtime manual.
              </p>
              <div className="mt-7">
                <Link
                  to="/docs"
                  className="inline-flex items-center gap-2 rounded-lg border border-[var(--color-border-strong)] px-4 py-2 text-sm font-medium text-[var(--color-fg-default)] no-underline transition-colors hover:bg-[var(--color-surface-hover)]"
                >
                  <span>Browse Docs Index</span>
                  <ArrowRight className="size-3.5" />
                </Link>
              </div>
            </div>

            <div className="divide-y divide-[var(--color-border-default)]">
              {FAQS.map((item) => (
                <div key={item.q} className="p-7 sm:p-8">
                  <h3 className="m-0 font-sans text-base font-semibold tracking-tight text-[var(--color-fg-default)]">
                    {item.q}
                  </h3>
                  <p className="mt-2.5 m-0 text-sm leading-relaxed text-[var(--color-fg-muted)]">
                    {item.a}
                  </p>
                </div>
              ))}
            </div>
          </div>
        </FrameCard>
      </section>

      {/* BOTTOM CTA */}
      <section className="border-t border-[var(--color-border-default)] py-24 text-center sm:py-28">
        <div className="mx-auto max-w-3xl px-5 sm:px-8">
          <h2 className="text-balance font-serif text-3xl font-normal leading-[1.12] tracking-[-0.02em] text-[var(--color-fg-default)] sm:text-5xl">
            Stop restarting the JVM just to swap a JAR.
          </h2>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
            <Link
              to="/docs/installation"
              className="inline-flex items-center gap-2 rounded-lg bg-[var(--color-brand-default)] px-5 py-2.5 text-sm font-medium text-white no-underline transition-colors hover:bg-[var(--color-brand-hover)]"
            >
              <span>Get Started</span>
              <ArrowRight className="size-4" />
            </Link>
            <a
              href="https://mutomenu.fy2ne.me"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 rounded-lg border border-[var(--color-border-strong)] px-5 py-2.5 text-sm font-medium text-[var(--color-fg-default)] no-underline transition-colors hover:bg-[var(--color-surface-hover)]"
            >
              <span>Get Muto Menu</span>
              <ArrowUpRight className="size-4" />
            </a>
          </div>
        </div>
      </section>
    </main>
  );
}
