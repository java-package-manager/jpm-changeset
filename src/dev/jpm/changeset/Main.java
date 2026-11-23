package dev.jpm.changeset;

import dev.jpm.api.client.JpmClient;
import dev.jpm.api.client.JpmError;
import dev.jpm.api.client.LoadOptions;
import dev.jpm.api.json.Jsonc;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jpm changeset}: jpm runs this as {@code jpm-changeset} with {@code JPM_EXE} and {@code JPM_PROJECT_DIR} set, and
 * it reads the project from that jpm over its protocol.
 */
public final class Main {
    /** The version in project.jsonc, from the {@code BuildConfig} class jpm generates ({@code "defineClass"}). */
    static final String VERSION = BuildConfig.VERSION;

    static final String HELP = """
            jpm changeset - versions and changelogs from changeset files

            Usage:
              jpm changeset [add]     Add a changeset: the modules that changed, how much, and a summary
                --module <name>       A module that changed (repeatable); asked for when left out
                --major | --minor | --patch
                --message, -m <text>  The summary for the changelog
                --empty               A changeset that releases nothing
              jpm changeset status    List the pending changesets and the versions they give
                --since <ref>         The branch to compare with (default: origin/main, else main)
                --check               Fail when a module changed since then has no changeset
              jpm changeset version   Apply the changesets: bump versions, write CHANGELOG.md, delete them
              jpm changeset publish [jpm publish options]
                                      Publish every module whose version has no tag yet, and tag it
                --tag-only            Only tag, without jpm publish (for projects released as Git tags)
              jpm changeset pre enter <tag>
                                      Prerelease mode: version releases 1.3.0-<tag>.0, .1, ... and keeps the changesets
              jpm changeset pre exit  The next version releases the stable versions and ends prerelease mode

            Groups, in the root project.jsonc: "extensions": { "changeset": {
              "fixed": [["core", "api"]],    always released together, at one version
              "linked": [["web", "admin"]],  those that are released share the highest new version
              "tagOnly": true                publish only tags, as --tag-only does } }
            """;

    private Main() {}

    static void step(String verb, String msg) {
        System.err.println(String.format("%12s", verb) + " " + msg);
    }

    static void info(String msg) {
        System.err.println(msg);
    }

    public static void main(String[] argv) {
        List<String> args = new ArrayList<>(List.of(argv));
        String cmd = args.isEmpty() || args.get(0).startsWith("-") ? "add" : args.remove(0);
        if (args.contains("--help") || args.contains("-h") || cmd.equals("help")) {
            System.out.print(HELP);
            return;
        }
        if (args.contains("--version")) {
            System.out.println("jpm-changeset " + VERSION);
            return;
        }
        Path root = Path.of(env("JPM_PROJECT_DIR", ".")).toAbsolutePath().normalize();
        try {
            int code = switch (cmd) {
                case "add" -> add(root, args);
                case "status" -> status(root, args);
                case "version" -> version(root, args);
                case "publish" -> publish(root, args);
                case "pre" -> pre(root, args);
                default -> throw new ChangesetException("unknown command \"" + cmd + "\"; see jpm changeset --help");
            };
            System.exit(code);
        } catch (ChangesetException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static Workspace load(Path root) {
        try (JpmClient jpm = JpmClient.start(List.of(env("JPM_EXE", "jpm"), "api"), root, Map.of(), "jpm-changeset", VERSION)) {
            return Workspace.of(jpm.load(root, LoadOptions.defaults()));
        } catch (JpmError e) {
            throw new ChangesetException("jpm could not load the project: " + e.getMessage());
        }
    }

    record AddOptions(List<String> modules, Bump bump, String message, boolean empty) {}

    static AddOptions parseAdd(List<String> args) {
        List<String> modules = new ArrayList<>();
        Bump bump = null;
        String message = null;
        boolean empty = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--module" -> modules.add(value(args, ++i, a));
                case "--major", "--minor", "--patch" -> {
                    Bump b = Bump.parse(a.substring(2));
                    if (bump != null && bump != b) throw new ChangesetException("choose one of --major, --minor and --patch");
                    bump = b;
                }
                case "--message", "-m" -> message = value(args, ++i, a);
                case "--empty" -> empty = true;
                default -> throw new ChangesetException("unknown option " + a + "; see jpm changeset --help");
            }
        }
        if (empty && (!modules.isEmpty() || bump != null)) throw new ChangesetException("an --empty changeset names no modules and no bump");
        return new AddOptions(modules, bump, message, empty);
    }

    private static String value(List<String> args, int i, String option) {
        if (i >= args.size()) throw new ChangesetException(option + " needs a value");
        return args.get(i);
    }

    static int add(Path root, List<String> args) {
        AddOptions o = parseAdd(args);
        Workspace ws = load(root);
        Console console = System.console();
        Map<String, Bump> releases = new LinkedHashMap<>();
        if (!o.empty()) {
            List<String> modules = new ArrayList<>(o.modules());
            if (modules.isEmpty() && ws.modules().size() == 1) modules.add(ws.modules().get(0).name());
            if (modules.isEmpty()) {
                if (console == null) throw new ChangesetException("name the modules that changed with --module (" + names(ws) + ")");
                String line = console.readLine("Which modules changed? (%s, separated by spaces): ", names(ws));
                if (line != null) for (String n : line.trim().split("[\\s,]+")) if (!n.isBlank()) modules.add(n);
                if (modules.isEmpty()) throw new ChangesetException("no module named");
            }
            for (String m : modules) {
                if (ws.module(m) == null) throw new ChangesetException("\"" + m + "\" is not a module of this project (" + names(ws) + ")");
            }
            Bump bump = o.bump();
            if (bump == null) {
                if (console == null) throw new ChangesetException("say how much with --major, --minor or --patch");
                String line = console.readLine("Major (breaking), minor (feature) or patch (fix)? [patch]: ");
                bump = line == null || line.isBlank() ? Bump.PATCH : Bump.parse(line);
            }
            for (String m : modules) releases.put(m, bump);
        }
        String message = o.message();
        if (message == null) {
            if (console == null) throw new ChangesetException("give a summary with --message");
            message = console.readLine("Summary: ");
            if (message == null || message.isBlank()) throw new ChangesetException("a changeset needs a summary");
        }
        Path dir = root.resolve(Changeset.DIR);
        String id = Changeset.newId(dir, new java.util.Random());
        Changeset c = new Changeset(id, releases, message);
        write(dir.resolve(id + ".md"), c.format());
        step("Added", Changeset.DIR + "/" + id + ".md" + (releases.isEmpty() ? " (releases nothing)"
                : " (" + String.join(", ", releases.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()) + ")"));
        return 0;
    }

    static Plan.Options options(Workspace ws, Pre pre) {
        return new Plan.Options(ws.groups("fixed"), ws.groups("linked"), pre);
    }

    static int pre(Path root, List<String> args) {
        if (args.isEmpty()) throw new ChangesetException("use `jpm changeset pre enter <tag>` or `jpm changeset pre exit`");
        Pre pre = Pre.read(root);
        switch (args.get(0)) {
            case "enter" -> {
                if (args.size() != 2 || !args.get(1).matches("[0-9A-Za-z-]+")) throw new ChangesetException("give the prerelease tag: `jpm changeset pre enter beta`");
                if (pre != null && !pre.exiting()) throw new ChangesetException("already in prerelease mode (" + pre.tag() + "); `jpm changeset pre exit` first");
                Workspace ws = load(root);
                Map<String, String> initial = new LinkedHashMap<>();
                for (Workspace.Module m : ws.modules()) initial.put(m.name(), m.version());
                new Pre(false, args.get(1), initial, pre == null ? new ArrayList<>() : new ArrayList<>(pre.released()), new LinkedHashMap<>()).write(root);
                step("Entered", "prerelease mode: `jpm changeset version` releases versions like 1.3.0-" + args.get(1) + ".0");
            }
            case "exit" -> {
                if (args.size() != 1) throw new ChangesetException("`jpm changeset pre exit` takes nothing more");
                if (pre == null || pre.exiting()) throw new ChangesetException("not in prerelease mode");
                new Pre(true, pre.tag(), pre.initialVersions(), pre.released(), pre.counters()).write(root);
                step("Exiting", "prerelease mode: the next `jpm changeset version` releases the stable versions");
            }
            default -> throw new ChangesetException("unknown pre command \"" + args.get(0) + "\"; use enter or exit");
        }
        return 0;
    }

    static int status(Path root, List<String> args) {
        String since = null;
        boolean check = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--since" -> since = value(args, ++i, "--since");
                case "--check" -> check = true;
                default -> throw new ChangesetException("unknown option " + args.get(i) + "; see jpm changeset --help");
            }
        }
        Workspace ws = load(root);
        List<Changeset> changesets = Changeset.readAll(root);
        Pre pre = Pre.read(root);
        Plan plan = Plan.of(ws, changesets, options(ws, pre));
        if (pre != null) info(pre.exiting() ? "Leaving prerelease mode (" + pre.tag() + "): the next version is stable." : "Prerelease mode: " + pre.tag());
        if (changesets.isEmpty()) info("No changesets.");
        for (Changeset c : changesets) {
            info(Changeset.DIR + "/" + c.id() + ".md  " + (c.releases().isEmpty() ? "(empty)"
                    : String.join(", ", c.releases().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList())));
        }
        for (Plan.Release r : plan.releases().values()) {
            step("Releases", r.module().name() + " " + r.module().version() + " -> " + r.newVersion() + " (" + r.bump() + ")");
        }
        if (!check) return 0;
        Git git = new Git(root);
        String base = since != null ? since : git.firstRef(List.of("origin/main", "main", "origin/master", "master"));
        if (base == null) throw new ChangesetException("no branch to compare with: pass --since <ref>");
        Set<String> covered = new LinkedHashSet<>();
        boolean anyEmpty = false;
        for (Changeset c : changesets) {
            covered.addAll(c.releases().keySet());
            anyEmpty |= c.releases().isEmpty();
        }
        Set<String> changed = new LinkedHashSet<>();
        for (Path f : git.changedSince(base)) {
            if (f.startsWith(root.resolve(Changeset.DIR)) || f.getFileName().toString().equals("CHANGELOG.md")) continue;
            Workspace.Module m = ws.moduleOf(f);
            if (m != null) changed.add(m.name());
        }
        String report = System.getenv("JPM_CHECK_PROBLEMS");
        boolean forJpm = report != null && !report.isBlank();
        List<Map<String, Object>> problems = new ArrayList<>();
        if (!anyEmpty) {
            for (String m : changed) {
                if (covered.contains(m)) continue;
                Workspace.Module mod = ws.module(m);
                if (released(git, base, mod)) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("severity", "error");
                p.put("message", m + " changed since " + base + ", but no changeset releases it");
                p.put("file", root.relativize(mod.dir().resolve("project.jsonc")).toString());
                p.put("hint", "add one with `jpm changeset add --module " + m + "`, or `jpm changeset add --empty` when it needs no release");
                problems.add(p);
                if (!forJpm) System.err.println("error: " + p.get("message") + "\n  hint: " + p.get("hint"));
            }
        }
        if (forJpm) write(Path.of(report), Jsonc.write(problems) + "\n");
        if (problems.isEmpty()) step("Checked", changed.isEmpty() ? "nothing changed since " + base : "every changed module has a changeset");
        return problems.isEmpty() ? 0 : 1;
    }

    /**
     * True if the module's version differs from the one at {@code base}: the change is a release, such as the
     * Version packages pull request, which deletes the changesets it applies.
     */
    static boolean released(Git git, String base, Workspace.Module mod) {
        String before = git.fileAtBase(base, mod.versionFile());
        String was = before == null ? null : Manifests.version(before);
        return was != null && !was.equals(mod.version());
    }

    static int version(Path root, List<String> args) {
        if (!args.isEmpty()) throw new ChangesetException("unknown option " + args.get(0) + "; see jpm changeset --help");
        Workspace ws = load(root);
        List<Changeset> changesets = Changeset.readAll(root);
        Pre pre = Pre.read(root);
        boolean prerelease = pre != null && !pre.exiting();
        List<Changeset> fresh = prerelease ? changesets.stream().filter(c -> !pre.released().contains(c.id())).toList() : changesets;
        if (fresh.isEmpty() && !(pre != null && pre.exiting() && !changesets.isEmpty())) {
            info("No changesets to apply.");
            return 0;
        }
        Plan plan = Plan.of(ws, changesets, options(ws, pre));
        Set<Path> bumped = new LinkedHashSet<>();
        for (Plan.Release r : plan.releases().values()) {
            if (bumped.add(r.module().versionFile())) write(r.module().versionFile(), Manifests.withVersion(read(r.module().versionFile()), r.newVersion()));
            Path changelog = r.module().dir().resolve("CHANGELOG.md");
            write(changelog, Plan.prepend(Files.exists(changelog) ? read(changelog) : null, r.module().name(), Plan.entry(r)));
            step("Bumped", r.module().name() + " " + r.module().version() + " -> " + r.newVersion());
        }
        if (prerelease) {
            List<String> released = new ArrayList<>(pre.released());
            for (Changeset c : fresh) released.add(c.id());
            Map<String, Integer> counters = new LinkedHashMap<>(pre.counters());
            for (String m : plan.releases().keySet()) counters.merge(m, 1, Integer::sum);
            new Pre(false, pre.tag(), pre.initialVersions(), released, counters).write(root);
        } else {
            for (Changeset c : changesets) {
                try {
                    Files.delete(root.resolve(Changeset.DIR).resolve(c.id() + ".md"));
                } catch (IOException e) {
                    throw new ChangesetException("could not delete " + c.id() + ".md: " + e.getMessage());
                }
            }
            if (pre != null) {
                try {
                    Files.delete(root.resolve(Changeset.DIR).resolve(Pre.FILE));
                } catch (IOException e) {
                    throw new ChangesetException("could not delete " + Pre.FILE + ": " + e.getMessage());
                }
                step("Left", "prerelease mode");
            }
        }
        step("Applied", fresh.size() + (fresh.size() == 1 ? " changeset" : " changesets")
                + (prerelease ? " as a prerelease (kept for the stable release)" : "")
                + "; review the changes and commit them, then run `jpm changeset publish`");
        return 0;
    }

    /**
     * How {@code publish} releases.
     *
     * @param tagOnly     tag without running jpm publish: the tag is the release, as for jpm extensions
     * @param publishArgs the options passed on to jpm publish
     */
    record PublishOptions(boolean tagOnly, List<String> publishArgs) {}

    static PublishOptions parsePublish(List<String> args, Map<String, Object> settings) {
        List<String> rest = new ArrayList<>(args);
        boolean tagOnly = rest.remove("--tag-only");
        Object setting = settings.get("tagOnly");
        if (setting != null && !(setting instanceof Boolean)) throw new ChangesetException("extensions.changeset.tagOnly must be true or false");
        tagOnly |= Boolean.TRUE.equals(setting);
        if (tagOnly && !rest.isEmpty()) throw new ChangesetException("--tag-only runs no jpm publish, so it takes no jpm publish options (" + String.join(" ", rest) + ")");
        return new PublishOptions(tagOnly, List.copyOf(rest));
    }

    static int publish(Path root, List<String> args) {
        Workspace ws = load(root);
        PublishOptions o = parsePublish(args, ws.settings());
        Git git = new Git(root);
        int published = 0;
        for (Workspace.Module m : ws.modules()) {
            if (!m.published()) continue;
            String tag = ws.modules().size() == 1 ? m.version() : m.name() + "@" + m.version();
            if (git.tagExists(tag)) continue;
            String message = tagMessage(m);
            if (o.tagOnly()) {
                git.tag(tag, message);
                step("Tagged", tag);
                published++;
                continue;
            }
            List<String> cmd = new ArrayList<>(List.of(env("JPM_EXE", "jpm"), "publish"));
            cmd.addAll(o.publishArgs());
            if (ws.modules().size() > 1) cmd.addAll(List.of("--module", m.name()));
            step("Publishing", m.name() + " " + m.version());
            int exit;
            try {
                exit = new ProcessBuilder(cmd).directory(root.toFile()).inheritIO().start().waitFor();
            } catch (IOException e) {
                throw new ChangesetException("could not run " + cmd.get(0) + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChangesetException("interrupted");
            }
            if (exit != 0) throw new ChangesetException("jpm publish failed for " + m.name() + " (exit " + exit + "); nothing was tagged for it");
            git.tag(tag, message);
            step("Tagged", tag);
            published++;
        }
        if (published == 0) info("Every published module's version is tagged already; nothing to publish.");
        else info("Push the tags with `git push --follow-tags`.");
        return 0;
    }

    /** The message of a module's release tag: {@code <name> <version>}, then its changelog entry, for release notes. */
    static String tagMessage(Workspace.Module m) {
        String subject = m.name() + " " + m.version();
        Path file = m.dir().resolve("CHANGELOG.md");
        try {
            String changes = Files.exists(file) ? Plan.changesOf(Files.readString(file), m.version()) : null;
            return changes == null ? subject : subject + "\n\n" + changes + "\n";
        } catch (IOException e) {
            throw new ChangesetException("could not read " + file + ": " + e.getMessage());
        }
    }

    private static String names(Workspace ws) {
        return String.join(", ", ws.modules().stream().map(Workspace.Module::name).toList());
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new ChangesetException("could not read " + f + ": " + e.getMessage());
        }
    }

    private static void write(Path f, String text) {
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Files.writeString(f, text);
        } catch (IOException e) {
            throw new ChangesetException("could not write " + f + ": " + e.getMessage());
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
