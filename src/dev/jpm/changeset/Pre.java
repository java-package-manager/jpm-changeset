package dev.jpm.changeset;

import dev.jpm.api.json.Jsonc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prerelease mode, kept in {@code .changeset/pre.json} between {@code pre enter} and the stable release after
 * {@code pre exit}.
 *
 * @param exiting         true after {@code pre exit}: the next {@code version} releases stable versions and ends it
 * @param tag             the prerelease tag, such as {@code beta}
 * @param initialVersions each module's version when prerelease mode began, which every prerelease is bumped from
 * @param released        the ids of the changesets already in a prerelease
 * @param counters        each module's number of prereleases so far (the {@code n} of {@code 1.3.0-beta.n})
 */
public record Pre(boolean exiting, String tag, Map<String, String> initialVersions, List<String> released, Map<String, Integer> counters) {
    public static final String FILE = "pre.json";

    /**
     * The prerelease state of a project, or null when it is not in prerelease mode.
     */
    @SuppressWarnings("unchecked")
    public static Pre read(Path root) {
        Path f = root.resolve(Changeset.DIR).resolve(FILE);
        if (!Files.isRegularFile(f)) return null;
        Map<String, Object> m;
        try {
            m = (Map<String, Object>) Jsonc.parse(Files.readString(f));
        } catch (IOException | Jsonc.ParseException | ClassCastException e) {
            throw new ChangesetException(Changeset.DIR + "/" + FILE + " is not prerelease state jpm changeset wrote: " + e.getMessage());
        }
        Map<String, String> initial = new LinkedHashMap<>();
        if (m.get("initialVersions") instanceof Map<?, ?> iv) iv.forEach((k, v) -> initial.put(String.valueOf(k), String.valueOf(v)));
        List<String> released = new ArrayList<>();
        if (m.get("changesets") instanceof List<?> l) for (Object o : l) released.add(String.valueOf(o));
        Map<String, Integer> counters = new LinkedHashMap<>();
        if (m.get("counters") instanceof Map<?, ?> c) c.forEach((k, v) -> counters.put(String.valueOf(k), v instanceof Number n ? n.intValue() : 0));
        return new Pre("exit".equals(m.get("mode")), String.valueOf(m.get("tag")), initial, released, counters);
    }

    /**
     * Writes this state into the project's {@code .changeset/}.
     */
    public void write(Path root) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", exiting ? "exit" : "pre");
        m.put("tag", tag);
        m.put("initialVersions", initialVersions);
        m.put("changesets", released);
        m.put("counters", counters);
        try {
            Files.createDirectories(root.resolve(Changeset.DIR));
            Files.writeString(root.resolve(Changeset.DIR).resolve(FILE), Jsonc.write(m) + "\n");
        } catch (IOException e) {
            throw new ChangesetException("could not write " + Changeset.DIR + "/" + FILE + ": " + e.getMessage());
        }
    }
}
