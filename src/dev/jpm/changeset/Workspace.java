package dev.jpm.changeset;

import dev.jpm.api.json.Jsonc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The modules of a project, as jpm reports them (project/load), with what jpm-changeset needs: which manifest holds
 * each module's version (its own, or the workspace root's when it inherits it) and which modules of the workspace it
 * depends on.
 *
 * @param root     the project's folder
 * @param modules  its modules, in jpm's build order
 * @param settings the root's {@code "extensions": { "changeset": { ... } }}
 */
public record Workspace(Path root, List<Module> modules, java.util.Map<String, Object> settings) {
    /**
     * A module.
     *
     * @param name        its name, as changesets name it
     * @param dir         its folder
     * @param version     its current version
     * @param versionFile the project.jsonc whose {@code "version"} it has
     * @param dependsOn   the workspace modules it depends on, by name
     * @param published   whether it is published (has a {@code "group"}), so {@code publish} releases it
     */
    public record Module(String name, Path dir, String version, Path versionFile, List<String> dependsOn, boolean published) {}

    /**
     * The workspace of a project jpm loaded.
     */
    public static Workspace of(dev.jpm.api.model.Project p) {
        List<Module> out = new ArrayList<>();
        Path rootManifest = p.dir().resolve("project.jsonc");
        for (dev.jpm.api.model.Module m : p.modules()) {
            Path own = m.dir().resolve("project.jsonc");
            Path versionFile = hasVersion(own) ? own : rootManifest;
            List<String> deps = new ArrayList<>();
            for (dev.jpm.api.model.Dependency d : m.dependencies()) {
                if ("workspace".equals(d.kind()) && d.module() != null && !deps.contains(d.module())) deps.add(d.module());
            }
            out.add(new Module(m.name(), m.dir(), m.version(), versionFile, List.copyOf(deps), m.group() != null));
        }
        java.util.Map<String, Object> settings = new java.util.LinkedHashMap<>();
        if (p.extensions().get("changeset") instanceof java.util.Map<?, ?> s) s.forEach((k, v) -> settings.put(String.valueOf(k), v));
        return new Workspace(p.dir(), List.copyOf(out), settings);
    }

    /**
     * The groups of a setting ({@code "fixed"} or {@code "linked"}): lists of module names.
     */
    public List<List<String>> groups(String key) {
        List<List<String>> out = new ArrayList<>();
        Object v = settings.get(key);
        if (v == null) return out;
        if (!(v instanceof List<?> groups)) throw new ChangesetException("extensions.changeset." + key + " must be a list of lists of module names");
        for (Object g : groups) {
            if (!(g instanceof List<?> names)) throw new ChangesetException("extensions.changeset." + key + " must be a list of lists of module names");
            out.add(names.stream().map(String::valueOf).toList());
        }
        return out;
    }

    /**
     * The module named {@code name}, or null.
     */
    public Module module(String name) {
        for (Module m : modules) if (m.name().equals(name)) return m;
        return null;
    }

    /**
     * The module whose folder holds {@code file} (the deepest one), or null.
     */
    public Module moduleOf(Path file) {
        Module best = null;
        Path f = file.toAbsolutePath().normalize();
        for (Module m : modules) {
            if (f.startsWith(m.dir()) && (best == null || m.dir().getNameCount() > best.dir().getNameCount())) best = m;
        }
        return best;
    }

    static boolean hasVersion(Path manifest) {
        if (!Files.isRegularFile(manifest)) return false;
        try {
            Jsonc.Node root = Jsonc.parseNode(Files.readString(manifest));
            return root.members != null && root.members.containsKey("version");
        } catch (IOException | Jsonc.ParseException e) {
            return false;
        }
    }
}
