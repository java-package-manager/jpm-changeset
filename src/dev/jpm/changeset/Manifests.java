package dev.jpm.changeset;

import dev.jpm.api.json.Jsonc;

/** Edits a project.jsonc in place: only the text of the value changes, so comments and layout stay. */
public final class Manifests {
    private Manifests() {}

    /** {@code source} with its top-level {@code "version"} set to {@code version}. */
    public static String withVersion(String source, String version) {
        Jsonc.Node root = Jsonc.parseNode(source);
        Jsonc.Member v = root.members == null ? null : root.members.get("version");
        if (v == null) throw new ChangesetException("the manifest has no \"version\" to bump");
        return source.substring(0, v.value.start) + Jsonc.quote(version) + source.substring(v.value.end);
    }
}
