package dev.jpm.changeset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One changeset: a Markdown file in {@code .changeset/} whose front matter names the modules it releases and how much,
 * followed by the summary that goes into their changelogs.
 *
 * <pre>{@code
 * ---
 * "core": minor
 * "app": patch
 * ---
 *
 * Add a cache for parsed manifests.
 * }</pre>
 *
 * @param id       its file name without {@code .md}
 * @param releases module name to bump; empty for an empty changeset
 * @param summary  the Markdown summary
 */
public record Changeset(String id, Map<String, Bump> releases, String summary) {
    public static final String DIR = ".changeset";

    /**
     * Reads a changeset file's text.
     */
    public static Changeset parse(String id, String text) {
        String t = text.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) throw new ChangesetException(id + ".md: a changeset starts with a --- line, then a line per module");
        int end = t.startsWith("---", 4) ? 4 : t.indexOf("\n---", 4) + 1;
        if (end <= 0) throw new ChangesetException(id + ".md: the front matter has no closing --- line");
        Map<String, Bump> releases = new LinkedHashMap<>();
        for (String line : t.substring(4, end).split("\n")) {
            if (line.isBlank()) continue;
            int colon = line.lastIndexOf(':');
            if (colon < 0) throw new ChangesetException(id + ".md: \"" + line + "\" is not \"module\": bump");
            String module = line.substring(0, colon).strip();
            if (module.length() >= 2 && (module.startsWith("\"") && module.endsWith("\"") || module.startsWith("'") && module.endsWith("'"))) {
                module = module.substring(1, module.length() - 1);
            }
            releases.merge(module, Bump.parse(line.substring(colon + 1)), Bump::max);
        }
        String rest = t.substring(end + 3);
        int nl = rest.indexOf('\n');
        String summary = (nl < 0 ? "" : rest.substring(nl + 1)).strip();
        return new Changeset(id, releases, summary);
    }

    /**
     * This changeset as the text of its file.
     */
    public String format() {
        StringBuilder sb = new StringBuilder("---\n");
        releases.forEach((m, b) -> sb.append('"').append(m).append("\": ").append(b).append('\n'));
        sb.append("---\n\n").append(summary.strip()).append('\n');
        return sb.toString();
    }

    /**
     * Every changeset in a project's {@code .changeset/} folder, by file name; README.md and other non-changesets are skipped.
     */
    public static List<Changeset> readAll(Path root) {
        Path dir = root.resolve(DIR);
        List<Changeset> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".md") || name.equalsIgnoreCase("README.md")) continue;
                out.add(parse(name.substring(0, name.length() - 3), Files.readString(f)));
            }
        } catch (IOException e) {
            throw new ChangesetException("could not read " + dir + ": " + e.getMessage());
        }
        return out;
    }

    private static final String[] ADJECTIVES = {"brave", "calm", "eager", "fancy", "gentle", "happy", "jolly", "kind", "lively",
            "merry", "nice", "proud", "quick", "silly", "tidy", "witty", "young", "zealous", "bright", "clever"};
    private static final String[] NOUNS = {"apples", "bees", "cats", "dogs", "eagles", "foxes", "geese", "hats", "islands",
            "jars", "kites", "lamps", "moons", "nets", "owls", "pens", "rivers", "stars", "trees", "waves"};

    /**
     * A readable file name that is not taken yet in {@code dir}, such as {@code brave-cats-sing}.
     */
    public static String newId(Path dir, java.util.random.RandomGenerator random) {
        for (int i = 0; ; i++) {
            String id = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + "-" + NOUNS[random.nextInt(NOUNS.length)] + "-"
                    + NOUNS[random.nextInt(NOUNS.length)] + (i < 20 ? "" : "-" + i);
            if (!Files.exists(dir.resolve(id + ".md"))) return id;
        }
    }
}
