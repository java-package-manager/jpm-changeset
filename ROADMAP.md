# Roadmap

What comes next for jpm-changeset. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md).

Status: `[ ]` open, `[~]` in progress, `[x]` done.

An extension that works like [changesets](https://github.com/changesets/changesets) for npm.
`.changeset/` is reserved by jpm.

- [x] `jpm changeset` (or `jpm changeset add`): ask which workspace modules changed, the bump
      (major/minor/patch) and a summary; write a Markdown file to `.changeset/`.
- [x] `jpm changeset version`: apply pending changesets: bump `version` in each `project.jsonc`, bump
      dependents inside the workspace, write `CHANGELOG.md` per module, delete the applied files.
- [x] `jpm changeset status`: list pending changesets. It is also a check in `jpm check`, which fails
      when a changed module has no changeset.
- [x] `jpm changeset publish`: publish bumped modules through jpm-publish and tag them.
- [x] Prerelease mode (`pre enter/exit`), and linked/fixed version groups.
- [x] A GitHub Action that opens and updates a "Version packages" pull request:
      [java-package-manager/changeset-action@v1](https://github.com/java-package-manager/changeset-action).
- [x] jpm and jpm-format release with it.
