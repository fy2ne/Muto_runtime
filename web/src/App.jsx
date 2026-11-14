import { useEffect, useState } from "react";
import { BrowserRouter, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { Navbar } from "./components/Navbar";
import { Footer } from "./components/Footer";
import { RuntimeLanding } from "./pages/RuntimeLanding";
import { DocsPage } from "./pages/DocsPage";
import { RUNTIME_DOC_SECTIONS } from "./data/runtimeDocs";

const RUNTIME_FOOTER_COLUMNS = [
  {
    title: "Documentation",
    items: [
      { label: "Overview", href: "/docs/overview" },
      { label: "Installation & Setup", href: "/docs/installation" },
      { label: "MutoApi Reference", href: "/docs/muto-api" },
      { label: "Lifecycle Events", href: "/docs/muto-events" },
    ],
  },
  {
    title: "Architecture",
    items: [
      { label: "JVMTI & Dynamic Mixins", href: "/docs/jvmti-mixins" },
      { label: "Knot & JarUnlocker", href: "/docs/knot-jarunlocker" },
      { label: "Registry Thawing", href: "/docs/registry-thawing" },
      { label: "Boundary Reload", href: "/docs/boundary-tiers" },
    ],
  },
  {
    title: "Ecosystem",
    items: [
      { label: "Muto Menu (UI)", href: "https://mutomenu.fy2ne.me" },
      { label: "Changelog 0.1.0-beta", href: "/docs/changelog" },
      { label: "License & FAQ", href: "/docs/license-faq" },
    ],
  },
  {
    title: "Source",
    items: [
      { label: "GitHub Repository ↗", href: "https://github.com/fy2ne/Muto_runtime" },
      { label: "Issue Tracker ↗", href: "https://github.com/fy2ne/Muto_runtime/issues" },
      { label: "Modrinth Project ↗", href: "https://modrinth.com/mod/muto" },
    ],
  },
];

function AppShell() {
  const { pathname } = useLocation();
  const inDocs = pathname.startsWith("/docs");
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem("muto-theme") || "dark";
    } catch {
      return "dark";
    }
  });

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
    document.documentElement.classList.toggle("dark", theme === "dark");
    document.documentElement.classList.toggle("light", theme !== "dark");
    try {
      localStorage.setItem("muto-theme", theme);
    } catch {
      // ignore storage failure
    }
  }, [theme]);

  useEffect(() => {
    if (!inDocs) {
      document.title = "Muto Runtime — Hot-Swap Fabric Mods Without Restarting";
    }
  }, [inDocs]);

  const toggleTheme = () => setTheme((prev) => (prev === "dark" ? "light" : "dark"));

  return (
    <div className="flex min-h-screen flex-col justify-between bg-[var(--color-canvas-default)] text-[var(--color-fg-default)]">
      <Navbar
        brandName="Muto"
        brandTag="0.1.0-beta"
        logoSrc="/logo-runtime.png"
        logoClass="brightness-0 dark:brightness-100"
        docsPath="/docs"
      />

      <Routes>
        <Route path="/" element={<RuntimeLanding />} />
        <Route
          path="/docs"
          element={<DocsPage sections={RUNTIME_DOC_SECTIONS} />}
        />
        <Route
          path="/docs/:slug"
          element={<DocsPage sections={RUNTIME_DOC_SECTIONS} />}
        />
        <Route path="/runtime/*" element={<Navigate to="/docs/overview" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>

      <Footer
        wordmark="Muto Runtime"
        columns={RUNTIME_FOOTER_COLUMNS}
        theme={theme}
        onToggleTheme={toggleTheme}
        licenseLabel="Elastic License 2.0 (ELv2)"
        companionHref="https://mutomenu.fy2ne.me"
        companionLabel="Muto Menu (UI) ↗"
      />
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppShell />
    </BrowserRouter>
  );
}
