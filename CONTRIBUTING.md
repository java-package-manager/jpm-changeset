# Contributing

- Run `jpm test` and `java checks/Comments.java check src test` before sending a change.
- The logic (`Changeset`, `Plan`, `Bump`, `Manifests`) has no git or jpm in it, so it is tested with protocol
  results as fixtures (`dev.jpm.api.client.Results`); `Main` and `Git` only wire it up.
- No dependencies besides jpm's client (`dev.jpm:jpm-api`).
- A release is a version change in `project.jsonc` on main: CI tags it, and `jpm add` resolves the tags.
