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
version has no tag yet, and tags it: `1.3.0` in a project with one module, `core@1.3.0` in a workspace. The
tag's message is the module's name and version, then its `CHANGELOG.md` entry, so release notes can be made
from it (changeset-action does). Push the tags with `git push --follow-tags`.

A project whose release is its Git tag, such as a jpm extension (`jpm add` installs those from their Git
source), needs no jpm publish: `jpm changeset publish --tag-only` only tags. To make that the project's
default, so `jpm changeset publish` and changeset-action's `publish: true` tag only, set it in
`jpm-changeset.jsonc` next to the root `project.jsonc`:

```jsonc
{ "tagOnly": true }
```

### In GitHub Actions

[changeset-action](https://github.com/java-package-manager/changeset-action) keeps a "Version packages"
pull request with what `jpm changeset version` changes, and publishes when it is merged:

```yaml
- uses: actions/checkout@v7
  with: { fetch-depth: 0 }
- uses: java-package-manager/setup-jpm@v1
- uses: java-package-manager/changeset-action@v1
  with: { publish: true }
```

## Prereleases

`jpm changeset pre enter beta` starts prerelease mode. Each `jpm changeset version` then releases versions
like `1.4.0-beta.0`, `1.4.0-beta.1`, … (each module counts its own), always bumped from the version before
prerelease mode, and keeps the changesets; a prerelease's changelog entry lists only the changesets no earlier
prerelease had. `jpm changeset pre exit` ends it: the next `version` releases `1.4.0` with every change
since prerelease mode began, and deletes the changesets. The state is in `.changeset/pre.json`; commit it.

## Groups

In `jpm-changeset.jsonc`, next to the root `project.jsonc`:

```jsonc
{
  "fixed": [["core", "api"]],      // always released together, at one version
  "linked": [["web", "admin"]]     // the ones that are released share the highest new version
}
```

A *fixed* group releases all its modules whenever one of them changes, at the highest version among them.
A *linked* group releases only the modules that changed, but gives them the same version.

## Checking

`jpm changeset status --check` fails when a module changed since the base branch (`--since <ref>`, by
default `origin/main`, else `main`) and no changeset releases it. It is also the `changeset:status` check of
`jpm check`, so CI catches a pull request that forgot its changeset:

```yaml
- run: jpm check --frozen
```

## Developing

```sh
jpm check     # the checks (jpm-format's formatting and lint among them), then the tests, as CI runs them
jpm format    # format the sources with jpm-format
jpm build
JPM_EXE=jpm JPM_PROJECT_DIR=/path/to/a/project jpm start -- status
```
