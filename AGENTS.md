# AGENTS.md

Facts for AI agents working on jpm-changeset. jpm's own rules are in
[jpm's AGENTS.md](https://github.com/java-package-manager/jpm/blob/main/AGENTS.md).

| What | Command |
|---|---|
| Checks (jpm-format's formatting and lint among them), then the tests, as CI runs them | `jpm check` |
| Test | `jpm test` |
| Format | `jpm format` |
| Build | `jpm build` |
| Comment lint | `java checks/Comments.java check src test` |
| Run on a project | `JPM_EXE=jpm JPM_PROJECT_DIR=<project> jpm start -- <command>` |

- `src/dev/jpm/changeset/Main.java`: the commands. `Changeset`: the files. `Plan`: what a release bumps and
  writes. `Workspace`: the modules, read from jpm (`JpmClient`, `jpm api`). `Manifests`: the version edit.
- No dependencies besides jpm's client (`dev.jpm:jpm-api`); no `//` or `/* */` comments in sources and tests.
- It never reads what a manifest means: modules, versions and dependencies come from jpm. It only finds and
  replaces the `"version"` value it bumps.
- Do not push, tag, or file issues without being asked.
