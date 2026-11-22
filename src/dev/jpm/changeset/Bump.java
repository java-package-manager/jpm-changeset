package dev.jpm.changeset;

/** How much a release raises a version: major for breaking changes, minor for features, patch for fixes. */
public enum Bump {
    PATCH, MINOR, MAJOR;

    /** The bump named in a changeset ({@code major}, {@code minor} or {@code patch}). */
    public static Bump parse(String s) {
        return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "major" -> MAJOR;
            case "minor" -> MINOR;
            case "patch" -> PATCH;
            default -> throw new ChangesetException("\"" + s.strip() + "\" is not a bump; use major, minor or patch");
        };
    }

    /** The larger of two bumps. */
    public Bump max(Bump other) {
        return other.ordinal() > ordinal() ? other : this;
    }

    /**
     * The version after this bump. A pre-release or build suffix is dropped; below 1.0.0 a major bump raises the minor
     * version, as the 0.x convention has it ("0.3.2" with a major change gives "0.4.0").
     */
    public String apply(String version) {
        String core = version.split("[-+]", 2)[0];
        String[] p = core.split("\\.");
        if (p.length != 3) throw new ChangesetException("\"" + version + "\" is not a version like 1.2.3");
        int major, minor, patch;
        try {
            major = Integer.parseInt(p[0]);
            minor = Integer.parseInt(p[1]);
            patch = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            throw new ChangesetException("\"" + version + "\" is not a version like 1.2.3");
        }
        Bump b = this == MAJOR && major == 0 ? MINOR : this;
        return switch (b) {
            case MAJOR -> (major + 1) + ".0.0";
            case MINOR -> major + "." + (minor + 1) + ".0";
            case PATCH -> major + "." + minor + "." + (patch + 1);
        };
    }

    /** The heading of this bump's section in a changelog. */
    public String heading() {
        return switch (this) {
            case MAJOR -> "Major Changes";
            case MINOR -> "Minor Changes";
            case PATCH -> "Patch Changes";
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
