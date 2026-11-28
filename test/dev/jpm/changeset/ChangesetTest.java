package dev.jpm.changeset;

import dev.jpm.api.client.Results;
import dev.jpm.api.json.Jsonc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangesetTest {
    @Test
    void bumpsVersions() {
        assertEquals("2.0.0", Bump.MAJOR.apply("1.4.2"));
        assertEquals("1.5.0", Bump.MINOR.apply("1.4.2"));
        assertEquals("1.4.3", Bump.PATCH.apply("1.4.2"));
        assertEquals("0.4.0", Bump.MAJOR.apply("0.3.9"), "below 1.0.0 a breaking change raises the minor version");
        assertEquals("1.0.1", Bump.PATCH.apply("1.0.0-SNAPSHOT"));
        assertThrows(ChangesetException.class, () -> Bump.PATCH.apply("1.0"));
        assertEquals(Bump.MAJOR, Bump.PATCH.max(Bump.MAJOR));
    }

    @Test
    void readsAndWritesChangesets() {
        Changeset c = Changeset.parse("brave-cats", "---\n\"core\": minor\napp: patch\n'core': patch\n---\n\nAdd a cache.\n\nIt is fast.\n");
        assertEquals(Map.of("core", Bump.MINOR, "app", Bump.PATCH), c.releases());
        assertEquals("Add a cache.\n\nIt is fast.", c.summary());
        assertEquals(c, Changeset.parse("brave-cats", c.format()));
        assertTrue(Changeset.parse("e", "---\n---\n\nNothing to release.\n").releases().isEmpty());
        assertThrows(ChangesetException.class, () -> Changeset.parse("x", "core: minor\n"));
        assertThrows(ChangesetException.class, () -> Changeset.parse("x", "---\ncore: huge\n---\n"));
    }

    @SuppressWarnings("unchecked")
    private static Workspace workspace(Path root) throws Exception {
        Files.writeString(root.resolve("project.jsonc"), "{ \"name\": \"shop\", \"version\": \"1.0.0\", \"workspace\": [\"core\", \"api\", \"app\", \"docs\"] }");
        for (String m : List.of("core", "api", "app", "docs")) Files.createDirectories(root.resolve(m));
        Files.writeString(root.resolve("core/project.jsonc"), "{ \"name\": \"core\", \"version\": \"1.2.0\" }");
        Files.writeString(root.resolve("api/project.jsonc"), "{ \"name\": \"api\" }");
        Files.writeString(root.resolve("app/project.jsonc"), "{ \"name\": \"app\" }");
        Files.writeString(root.resolve("docs/project.jsonc"), "{\n  // the docs site\n  \"name\": \"docs\",\n  \"version\": \"0.3.0\"\n}\n");
        String load = """
                { "dir": "%s", "name": "shop", "version": "1.0.0", "workspace": true, "modules": [
                  { "name": "core", "group": "g", "version": "1.2.0", "dir": "%s/core" },
                  { "name": "api", "group": "g", "version": "1.0.0", "dir": "%s/api",
                    "dependencies": [{ "key": "workspace:core", "kind": "workspace", "module": "core" }] },
                  { "name": "app", "version": "1.0.0", "dir": "%s/app",
                    "dependencies": [{ "key": "workspace:api", "kind": "workspace", "module": "api" }] },
                  { "name": "docs", "version": "0.3.0", "dir": "%s/docs" } ] }
                """.formatted(root, root, root, root, root);
        return Workspace.of(Results.project((Map<String, Object>) Jsonc.parse(load)));
    }

    @Test
    void readsSettingsFromItsOwnFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jpm-changeset.jsonc"), """
                { "fixed": [["core", "api"]], "tagOnly": true }
                """);
        Workspace ws = workspace(root);
        assertEquals(List.of(List.of("core", "api")), ws.groups("fixed"));
        assertEquals(new Main.PublishOptions(true, List.of()), Main.parsePublish(List.of(), ws.settings()));
    }

    @Test
    void plansReleases(@TempDir Path root) throws Exception {
        Workspace ws = workspace(root);
        assertEquals(root.resolve("project.jsonc"), ws.module("api").versionFile(), "api inherits the root's version");
        Plan plan = Plan.of(ws, List.of(
                Changeset.parse("a", "---\ncore: minor\n---\n\nAdd a cache.\n"),
                Changeset.parse("b", "---\ncore: patch\n---\n\nFix a leak.\n")));
        assertEquals(List.of("core", "api", "app"), List.copyOf(plan.releases().keySet()),
                "api depends on core; app shares api's version, so it is released with it");
        assertEquals("1.3.0", plan.releases().get("core").newVersion());
        assertEquals("1.0.1", plan.releases().get("api").newVersion());
        assertEquals("1.0.1", plan.releases().get("app").newVersion());
        assertEquals(List.of("core@1.3.0"), plan.releases().get("api").updated());
        assertEquals(List.of(), plan.releases().get("app").updated(), "api shares app's version, so it is always released with it");
        assertEquals("## 1.3.0\n\n### Minor Changes\n\n- Add a cache.\n\n### Patch Changes\n\n- Fix a leak.\n", Plan.entry(plan.releases().get("core")));
        assertEquals("## 1.0.1\n\n### Patch Changes\n\n- Updated dependencies: core@1.3.0\n", Plan.entry(plan.releases().get("api")));
        ChangesetException e = assertThrows(ChangesetException.class, () -> Plan.of(ws, List.of(Changeset.parse("c", "---\nweb: patch\n---\n\nx\n"))));
        assertTrue(e.getMessage().contains("\"web\", which is not a module"), e.getMessage());
        Plan shared = Plan.of(ws, List.of(Changeset.parse("d", "---\napp: minor\n---\n\nNew page.\n")));
        assertEquals("1.1.0", shared.releases().get("api").newVersion(), "api shares app's version file");
    }

    @Test
    void writesChangelogsAndVersions() {
        String first = Plan.prepend(null, "core", "## 1.1.0\n\n### Minor Changes\n\n- One.\n");
        assertEquals("# core\n\n## 1.1.0\n\n### Minor Changes\n\n- One.\n", first);
        String second = Plan.prepend(first, "core", "## 1.2.0\n\n### Patch Changes\n\n- Two.\n");
        assertEquals("# core\n\n## 1.2.0\n\n### Patch Changes\n\n- Two.\n\n## 1.1.0\n\n### Minor Changes\n\n- One.\n", second);
        String manifest = "{\n  // the docs site\n  \"name\": \"docs\",\n  \"version\": \"0.3.0\", // bumped by changesets\n  \"java\": \"21\"\n}\n";
        assertEquals(manifest.replace("\"0.3.0\"", "\"0.4.0\""), Manifests.withVersion(manifest, "0.4.0"));
        assertThrows(ChangesetException.class, () -> Manifests.withVersion("{ \"name\": \"x\" }", "1.0.0"));
    }

    @Test
    void parsesAddOptions() {
        Main.AddOptions o = Main.parseAdd(List.of("--module", "core", "--module", "api", "--minor", "-m", "Add a cache."));
        assertEquals(new Main.AddOptions(List.of("core", "api"), Bump.MINOR, "Add a cache.", false), o);
        assertThrows(ChangesetException.class, () -> Main.parseAdd(List.of("--minor", "--patch")));
        assertThrows(ChangesetException.class, () -> Main.parseAdd(List.of("--empty", "--module", "core")));
        assertThrows(ChangesetException.class, () -> Main.parseAdd(List.of("--loud")));
    }

    @Test
    void findsAVersionsChanges() {
        String changelog = "# core\n\n## 1.3.0\n\n### Minor changes\n\n- Add a cache.\n\n## 1.2.0\n\n### Patch changes\n\n- Fix it.\n";
        assertEquals("### Minor changes\n\n- Add a cache.", Plan.changesOf(changelog, "1.3.0"));
        assertEquals("### Patch changes\n\n- Fix it.", Plan.changesOf(changelog, "1.2.0"));
        assertNull(Plan.changesOf(changelog, "1.1.0"));
        assertNull(Plan.changesOf(null, "1.3.0"));
    }

    @Test
    void parsesPublishOptions() {
        assertEquals(new Main.PublishOptions(false, List.of("central")), Main.parsePublish(List.of("central"), Map.of()));
        assertEquals(new Main.PublishOptions(true, List.of()), Main.parsePublish(List.of("--tag-only"), Map.of()));
        assertEquals(new Main.PublishOptions(true, List.of()), Main.parsePublish(List.of(), Map.of("tagOnly", true)));
        assertEquals(new Main.PublishOptions(false, List.of()), Main.parsePublish(List.of(), Map.of("tagOnly", false)));
        assertThrows(ChangesetException.class, () -> Main.parsePublish(List.of("--tag-only", "central"), Map.of()));
        assertThrows(ChangesetException.class, () -> Main.parsePublish(List.of(), Map.of("tagOnly", "yes")));
    }

    @Test
    void groupsReleaseTogether(@TempDir Path root) throws Exception {
        Workspace ws = workspace(root);
        Changeset core = Changeset.parse("a", "---\ncore: minor\n---\n\nAdd a cache.\n");
        Plan fixed = Plan.of(ws, List.of(core), new Plan.Options(List.of(List.of("core", "docs")), List.of(), null));
        assertEquals("1.3.0", fixed.releases().get("core").newVersion());
        assertEquals("1.3.0", fixed.releases().get("docs").newVersion(), "a fixed group shares the highest version, bumped");
        Changeset both = Changeset.parse("b", "---\ncore: patch\ndocs: minor\n---\n\nx\n");
        Plan linked = Plan.of(ws, List.of(both), new Plan.Options(List.of(), List.of(List.of("core", "docs")), null));
        assertEquals("1.2.1", Bump.PATCH.apply("1.2.0"));
        assertEquals("1.2.1", linked.releases().get("core").newVersion(), "core 1.2.1 is higher than docs 0.4.0");
        assertEquals("1.2.1", linked.releases().get("docs").newVersion());
        Plan alone = Plan.of(ws, List.of(core), new Plan.Options(List.of(), List.of(List.of("core", "docs")), null));
        assertTrue(!alone.releases().containsKey("docs"), "a linked group does not release members without changes");
        assertThrows(ChangesetException.class, () -> Plan.of(ws, List.of(core), new Plan.Options(List.of(List.of("core", "web")), List.of(), null)));
    }

    @Test
    void prereleasesCountUpAndEndInAStableVersion(@TempDir Path root) throws Exception {
        Workspace ws = workspace(root);
        Changeset one = Changeset.parse("one", "---\ncore: minor\n---\n\nAdd a cache.\n");
        Changeset two = Changeset.parse("two", "---\ncore: patch\n---\n\nFix the cache.\n");
        Map<String, String> initial = Map.of("core", "1.2.0", "api", "1.0.0", "app", "1.0.0", "docs", "0.3.0");
        Pre first = new Pre(false, "beta", initial, List.of(), Map.of());
        Plan p1 = Plan.of(ws, List.of(one), new Plan.Options(List.of(), List.of(), first));
        assertEquals("1.3.0-beta.0", p1.releases().get("core").newVersion());
        assertEquals("1.0.1-beta.0", p1.releases().get("api").newVersion());
        Pre second = new Pre(false, "beta", initial, List.of("one"), Map.of("core", 1, "api", 1, "app", 1));
        Plan p2 = Plan.of(ws, List.of(one, two), new Plan.Options(List.of(), List.of(), second));
        assertEquals("1.3.0-beta.1", p2.releases().get("core").newVersion(), "still bumped from 1.2.0, the version before prerelease mode");
        assertEquals("## 1.3.0-beta.1\n\n### Patch Changes\n\n- Fix the cache.\n", Plan.entry(p2.releases().get("core")),
                "only the changesets no prerelease had yet");
        Pre exit = new Pre(true, "beta", initial, List.of("one", "two"), Map.of("core", 2));
        Plan stable = Plan.of(ws, List.of(one, two), new Plan.Options(List.of(), List.of(), exit));
        assertEquals("1.3.0", stable.releases().get("core").newVersion());
        assertTrue(Plan.entry(stable.releases().get("core")).contains("- Add a cache.") && Plan.entry(stable.releases().get("core")).contains("- Fix the cache."));
    }

    @Test
    void keepsThePrereleaseState(@TempDir Path root) {
        Pre p = new Pre(false, "rc", Map.of("core", "1.2.0"), List.of("one"), Map.of("core", 2));
        p.write(root);
        assertEquals(p, Pre.read(root));
        assertNull(Pre.read(root.resolve("elsewhere")));
    }

    @Test
    void aVersionBumpSinceTheBaseIsARelease(@TempDir Path root) throws Exception {
        Git git = new Git(root);
        Path manifest = root.resolve("project.jsonc");
        Files.writeString(manifest, "{ \"name\": \"core\", \"version\": \"1.0.0\" }\n");
        for (String[] a : List.of(new String[]{"init", "-q", "-b", "main"}, new String[]{"add", "-A"},
                new String[]{"-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "commit", "-qm", "1.0.0"},
                new String[]{"switch", "-q", "-c", "release"})) {
            assertEquals(0, git.run(a).exit());
        }
        Files.writeString(manifest, "{ \"name\": \"core\", \"version\": \"1.0.1\" }\n");
        assertTrue(Main.released(git, "main", new Workspace.Module("core", root, "1.0.1", manifest, List.of(), false)));
        assertFalse(Main.released(git, "main", new Workspace.Module("core", root, "1.0.0", manifest, List.of(), false)));
        assertEquals("1.0.1", Manifests.version(Files.readString(manifest)));
        assertNull(Manifests.version("{ \"name\": \"core\" }"));
    }
}
