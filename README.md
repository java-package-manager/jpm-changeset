# jpm-changeset

Versions and changelogs for [jpm](https://github.com/java-package-manager/jpm) projects, the way
[changesets](https://github.com/changesets/changesets) does it for npm: each change that should be released
comes with a small file that says which modules it touches, how much (major, minor or patch) and what to write
in the changelog. Releasing applies them all at once.

```sh
jpm add github:java-package-manager/jpm-changeset   # declare it in the project (needs jpm 0.12.0 or newer)
```

## Use

```sh
jpm changeset add --module core --minor -m "Add a cache for parsed manifests."
jpm changeset status        # the pending changesets and the versions they give
jpm changeset version       # bump the versions, write CHANGELOG.md, delete the changesets
jpm changeset publish       # publish what has no tag yet with jpm publish, and tag it
```

`jpm changeset` alone is `jpm changeset add`. In a terminal it asks for the modules, the bump and the
summary; in scripts, `--module` (repeatable), `--major`/`--minor`/`--patch` and `--message` say them. A
project with one module needs no `--module`. `--empty` records that a change needs no release.

A changeset is a Markdown file in `.changeset/` with a random name. Commit it with the change:

```md
---
"core": minor
"api": patch
---

Add a cache for parsed manifests.
```

## Releasing

`jpm changeset version` applies every pending changeset:

- each named module's `"version"` rises by the largest bump it has (below 1.0.0, a breaking change raises
  the minor version); only the value in `project.jsonc` changes, so comments and layout stay;
- a workspace module that depends on a released one gets a patch release that names the new version;
- modules that inherit their version from the workspace root are released together;
- each released module's `CHANGELOG.md` gets an entry at the top, grouped into major, minor and patch
  changes;
- the applied changesets are deleted.

Review and commit that, then `jpm changeset publish [jpm publish options]` runs
[jpm-publish](https://github.com/java-package-manager/jpm-publish) for every module with a `"group"` whose
version has no tag yet, and tags it: `1.3.0` in a project with one module, `core@1.3.0` in a workspace. Push
the tags with `git push --follow-tags`.

## Checking

`jpm changeset status --check` fails when a module changed since the base branch (`--since <ref>`, by
default `origin/main`, else `main`) and no changeset releases it. It is also the `changeset:status` check of
`jpm check`, so CI catches a pull request that forgot its changeset:

```yaml
- run: jpm check --frozen
```

## Developing

```sh
jpm test
jpm build
JPM_EXE=jpm JPM_PROJECT_DIR=/path/to/a/project jpm start -- status
```
