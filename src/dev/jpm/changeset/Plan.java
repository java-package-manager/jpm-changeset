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
     * @param updated    the workspace modules it depends on that are released too, as name@version; not the ones that share
     *                   its version (the same manifest's, or a fixed group's), which are always released with it
     */
    public record Release(Workspace.Module module, Bump bump, String newVersion, Map<Bump, List<String>> changes, List<String> updated) {}

    /**
     * How releases are grouped, and the prerelease state.
     *
     * @param fixed  groups of modules that always release together, at one version
     * @param linked groups whose released members share the highest new version among them
     * @param pre    the prerelease state, or null
     */
    public record Options(List<List<String>> fixed, List<List<String>> linked, Pre pre) {
        public static Options none() {
            return new Options(List.of(), List.of(), null);
        }
    }

    /** The plan for these changesets; a changeset that names a module the workspace does not have is an error. */
    public static Plan of(Workspace ws, List<Changeset> changesets) {
        return of(ws, changesets, Options.none());
    }

    /** The plan for these changesets, with groups and prerelease mode. */
    public static Plan of(Workspace ws, List<Changeset> changesets, Options o) {
        for (List<String> g : concat(o.fixed(), o.linked())) {
            for (String n : g) if (ws.module(n) == null) throw new ChangesetException("the group " + g + " names \"" + n + "\", which is not a module of this project");
        }
        Pre pre = o.pre();
        Map<String, Bump> bumps = new LinkedHashMap<>();
        Map<String, Map<Bump, List<String>>> changes = new LinkedHashMap<>();
        for (Changeset c : changesets) {
            boolean inLog = pre == null || pre.exiting() || !pre.released().contains(c.id());
            for (Map.Entry<String, Bump> e : c.releases().entrySet()) {
                if (ws.module(e.getKey()) == null) {
                    throw new ChangesetException(Changeset.DIR + "/" + c.id() + ".md names \"" + e.getKey() + "\", which is not a module of this project"
                            + " (" + String.join(", ", ws.modules().stream().map(Workspace.Module::name).toList()) + ")");
                }
                bumps.merge(e.getKey(), e.getValue(), Bump::max);
                if (inLog && !c.summary().isBlank()) {
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
            for (List<String> g : o.fixed()) {
                Bump max = null;
                for (String n : g) if (bumps.containsKey(n)) max = max == null ? bumps.get(n) : max.max(bumps.get(n));
                if (max == null) continue;
                for (String n : g) {
                    if (bumps.get(n) != max) {
                        bumps.put(n, max);
                        grew = true;
                    }
                }
            }
        }
        Map<String, String> next = new LinkedHashMap<>();
        for (Workspace.Module m : ws.modules()) {
            Bump b = bumps.get(m.name());
            if (b != null) next.put(m.name(), b.apply(base(m, pre)));
        }
        List<List<String>> shared = new ArrayList<>(concat(o.fixed(), o.linked()));
        Map<Path, List<String>> byFile = new LinkedHashMap<>();
        for (Workspace.Module m : ws.modules()) byFile.computeIfAbsent(m.versionFile(), k -> new ArrayList<>()).add(m.name());
        shared.addAll(byFile.values());
        for (List<String> g : shared) {
            String max = null;
            for (String n : g) if (next.containsKey(n) && (max == null || compare(next.get(n), max) > 0)) max = next.get(n);
            if (max == null) continue;
            for (String n : g) if (next.containsKey(n)) next.put(n, max);
        }
        Map<String, Release> out = new LinkedHashMap<>();
        for (Workspace.Module m : ws.modules()) {
            Bump b = bumps.get(m.name());
            if (b == null) continue;
            String v = next.get(m.name());
            if (pre != null && !pre.exiting()) v = v + "-" + pre.tag() + "." + pre.counters().getOrDefault(m.name(), 0);
            out.put(m.name(), new Release(m, b, v, changes.getOrDefault(m.name(), Map.of()), new ArrayList<>()));
        }
        for (Release r : out.values()) {
            Set<String> updated = new LinkedHashSet<>();
            for (String d : r.module().dependsOn()) {
                Release dep = out.get(d);
                if (dep == null || dep.module().versionFile().equals(r.module().versionFile()) || inOneGroup(o.fixed(), d, r.module().name())) continue;
                updated.add(d + "@" + dep.newVersion());
            }
            r.updated().addAll(updated);
        }
        return new Plan(out);
    }

    private static boolean inOneGroup(List<List<String>> groups, String a, String b) {
        for (List<String> g : groups) if (g.contains(a) && g.contains(b)) return true;
        return false;
    }

    private static String base(Workspace.Module m, Pre pre) {
        return pre == null ? m.version() : pre.initialVersions().getOrDefault(m.name(), m.version());
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Compares two versions by their major, minor and patch numbers. */
    static int compare(String a, String b) {
        String[] x = a.split("[-+]", 2)[0].split("\\."), y = b.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) return c;
        }
        return 0;
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

    /**
     * The changes {@code changelog} lists for {@code version}: the text under its {@code ## <version>} heading, up to the
     * next version's, without the heading; null when it has no entry for that version.
     */
    public static String changesOf(String changelog, String version) {
        if (changelog == null) return null;
        List<String> lines = List.of(changelog.replace("\r\n", "\n").split("\n", -1));
        int start = lines.indexOf("## " + version);
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) sb.append(lines.get(i)).append('\n');
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
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
