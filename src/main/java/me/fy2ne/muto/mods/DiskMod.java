package me.fy2ne.muto.mods;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record DiskMod(
        String id,
        String name,
        String version,
        Path filePath,
        boolean disabled,
        boolean activeInLoader,
        boolean isCore,
        Map<String, String> depends,
        Set<String> aliases,
        String iconPath,
        String description,
        List<String> authors,
        Map<String, String> links,
        Set<String> license
) {
    // back-compat: old 10-field callers
    public DiskMod(
            String id, String name, String version, Path filePath,
            boolean disabled, boolean activeInLoader, boolean isCore,
            Map<String, String> depends, Set<String> aliases, String iconPath
    ) {
        this(id, name, version, filePath, disabled, activeInLoader, isCore, depends, aliases,
                iconPath, "", Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());
    }

    public DiskMod(
            String id, String name, String version, Path filePath,
            boolean disabled, boolean activeInLoader, boolean isCore,
            Map<String, String> depends, Set<String> aliases
    ) {
        this(id, name, version, filePath, disabled, activeInLoader, isCore, depends, aliases,
                null, "", Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());
    }

    public DiskMod(
            String id, String name, String version, Path filePath,
            boolean disabled, boolean activeInLoader, boolean isCore,
            Map<String, String> depends
    ) {
        this(id, name, version, filePath, disabled, activeInLoader, isCore, depends,
                Collections.emptySet(), null, "", Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());
    }

    public DiskMod(
            String id, String name, String version, Path filePath,
            boolean disabled, boolean activeInLoader, boolean isCore
    ) {
        this(id, name, version, filePath, disabled, activeInLoader, isCore,
                Collections.emptyMap(), Collections.emptySet(), null, "", Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());
    }

    public boolean isNew() {
        return !disabled && !activeInLoader;
    }
}
