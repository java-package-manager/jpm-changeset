package dev.jpm.changeset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The git commands jpm-changeset runs, in the project's folder.
 */
final class Git {
    private final Path dir;

    Git(Path dir) {
        this.dir = dir;
    }

    record Result(int exit, String out) {
    }

    Result run(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(p.waitFor(), out);
        } catch (IOException e) {
            throw new ChangesetException("could not run git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChangesetException("interrupted");
        }
    }

    /**
     * The first of these refs that exists, or null.
     */
    String firstRef(List<String> refs) {
        for (String r : refs) if (run("rev-parse", "--verify", "--quiet", r + "^{commit}").exit() == 0) return r;
        return null;
    }

    /**
     * The files changed since {@code base} (from where this branch left it), committed or not, relative to the repository.
     */
    List<Path> changedSince(String base) {
        Result top = run("rev-parse", "--show-toplevel");
        if (top.exit() != 0) throw new ChangesetException("not in a git repository: " + top.out().strip());
        Path root = Path.of(top.out().strip());
        List<Path> out = new ArrayList<>();
        for (String spec : new String[]{base + "...HEAD", null}) {
            Result r = spec == null ? run("status", "--porcelain", "--untracked-files=all") : run("diff", "--name-only", spec);
            if (r.exit() != 0)
                throw new ChangesetException("git could not compare with " + base + ": " + r.out().strip());
            for (String line : r.out().split("\n")) {
                if (line.isBlank()) continue;
                String file = spec == null ? line.substring(3).replaceFirst("^.* -> ", "") : line;
                Path p = root.resolve(file.strip()).normalize();
                if (!out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    /**
     * A file as it was where this branch left {@code base}, or null if it did not exist there.
     */
    String fileAtBase(String base, Path file) {
        Result mb = run("merge-base", base, "HEAD");
        if (mb.exit() != 0) return null;
        String rel = dir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        Result r = run("show", mb.out().strip() + ":./" + rel);
        return r.exit() == 0 ? r.out() : null;
    }

    boolean tagExists(String tag) {
        return run("rev-parse", "--verify", "--quiet", "refs/tags/" + tag).exit() == 0;
    }

    void tag(String tag, String message) {
        Result r = run("tag", "-a", "--cleanup=verbatim", tag, "-m", message);
        if (r.exit() != 0) throw new ChangesetException("git could not create the tag " + tag + ": " + r.out().strip());
    }
}
