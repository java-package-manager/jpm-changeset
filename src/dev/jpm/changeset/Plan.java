package dev.jpm.changeset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the pending changesets release: each module's new version and the lines of its changelog entry. A module that
 * depends on a released module of the workspace gets a patch release itself; modules that share a version (they
 * inherit the workspace root's) are released together, with the largest bump among them.
 *
 * @param releases module name to its release, in the workspace's order
 */
public record Plan(Map<String, Release> releases) {
    /**
     * One module's release.
     *
     * @param module     the module
     * @param bump       how much its version rises
     * @param newVersion its version after the release
     * @param changes    bump to the summaries of its changesets
     * @param updated    the workspace modules it depends on that are released too, as name@version
     */
    public record Release(Workspace.Module module, Bump bump, String newVersion, Map<Bump, List<String>> changes, List<String> updated) {}

    /** The plan for these changesets; a changeset that names a module the workspace does not have is an error. */
    public static Plan of(Workspace ws, List<Changeset> changesets) {
        Map<String, Bump> bumps = new LinkedHashMap<>();
        Map<String, Map<Bump, List<String>>> changes = new LinkedHashMap<>();
        for (Changeset c : changesets) {
            for (Map.Entry<String, Bump> e : c.releases().entrySet()) {
                if (ws.module(e.getKey()) == null) {
                    throw new ChangesetException(Changeset.DIR + "/" + c.id() + ".md names \"" + e.getKey() + "\", which is not a module of this project"
                            + " (" + String.join(", ", ws.modules().stream().map(Workspace.Module::name).toList()) + ")");
                }
                bumps.merge(e.getKey(), e.getValue(), Bump::max);
                if (!c.summary().isBlank()) {
                    changes.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(c.summary());
                }
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Workspace.Module m : ws.modules()) {
                if (!bumps.containsKey(m.name()) && m.dependsOn().stream().anyMatch(bumps::containsKey)) {
                    bumps.put(m.name(), Bump.PATCH);
                    grew = true;
                }
            }
            Map<Path, Bump> byFile = new LinkedHashMap<>();
            for (Workspace.Module m : ws.modules()) if (bumps.containsKey(m.name())) byFile.merge(m.versionFile(), bumps.get(m.name()), Bump::max);
            for (Workspace.Module m : ws.modules()) {
                Bump shared = byFile.get(m.versionFile());
                if (shared != null && shared != bumps.get(m.name())) {
                    bumps.put(m.name(), shared);
                    grew = true;
                }
            }
        }
        Map<String, Release> out = new LinkedHashMap<>();
        for (Workspace.Module m : ws.modules()) {
            Bump b = bumps.get(m.name());
            if (b == null) continue;
            out.put(m.name(), new Release(m, b, b.apply(m.version()), changes.getOrDefault(m.name(), Map.of()), new ArrayList<>()));
        }
        for (Release r : out.values()) {
            Set<String> updated = new LinkedHashSet<>();
            for (String d : r.module().dependsOn()) if (out.containsKey(d)) updated.add(d + "@" + out.get(d).newVersion());
            r.updated().addAll(updated);
        }
        return new Plan(out);
    }

    /** The changelog entry of one release, as Markdown, starting with its version heading. */
    public static String entry(Release r) {
        StringBuilder sb = new StringBuilder("## ").append(r.newVersion()).append("\n");
        for (Bump b : List.of(Bump.MAJOR, Bump.MINOR, Bump.PATCH)) {
            List<String> lines = new ArrayList<>(r.changes().getOrDefault(b, List.of()));
            if (b == Bump.PATCH && !r.updated().isEmpty()) lines.add("Updated dependencies: " + String.join(", ", r.updated()));
            if (lines.isEmpty()) continue;
            sb.append("\n### ").append(b.heading()).append("\n\n");
            for (String l : lines) sb.append("- ").append(l.strip().replace("\n", "\n  ")).append('\n');
        }
        return sb.toString();
    }

    /** {@code changelog} with the entry added at the top, under its {@code # <module>} title (which it gets when it has none). */
    public static String prepend(String changelog, String module, String entry) {
        String title = "# " + module + "\n";
        if (changelog == null || changelog.isBlank()) return title + "\n" + entry;
        String c = changelog.replace("\r\n", "\n");
        if (c.startsWith("# ")) {
            int nl = c.indexOf('\n');
            String head = nl < 0 ? c + "\n" : c.substring(0, nl + 1);
            String rest = nl < 0 ? "" : c.substring(nl + 1).replaceFirst("^\n+", "");
            return head + "\n" + entry + (rest.isEmpty() ? "" : "\n" + rest);
        }
        return title + "\n" + entry + "\n" + c;
    }
}
