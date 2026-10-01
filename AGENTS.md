# Development guide

This repository contains small, server-only Carpet patches. Use Java 25 and the committed Gradle Wrapper. Local Minecraft tests run in WSL; copy the checkout into a native Linux build directory to keep Gradle caches and generated files off the Windows sync tree.

## Structure

- `src/main/java/.../PrivatePatches.java`: Carpet extension and lifecycle forwarding.
- `src/main/java/.../patches/<feature>/`: one user-visible feature, its rule, implementation and Mixins.
- `docs/patches/`: evidence, behavior, limitations and regression coverage for each feature.
- `tests/unit/`: JUnit contracts.
- `tests/e2e/`: real Minecraft server and client test code, fixtures and orchestration.
- `versions/`: pinned target dependencies. `main` owns both supported Minecraft versions.

## Implementation

- One functional patch has one opt-in Carpet rule. Both player-retention fixes share `playerRetentionMemoryLeakFix`.
- `fixBlueMap` is a separate feature: forward fake-player disconnects through Fabric's idempotent `handleDisconnect`. Do not emit extra JOIN events or introduce a BlueMap production dependency.
- Keep code within a feature together. Add another package and explicit registration for a new feature; do not add a patch framework or automatic discovery.
- Use `carpet.api.settings`. SettingsManager owns commands and persistent world configuration.
- Check the rule in every behavior-changing entry point. A startup-only Mixin condition is not a runtime toggle.
- Mutate maps on the server thread. Do not keep static strong references to players, connections, worlds or servers.
- Use exact player instances and removal state, never names or UUIDs alone, for cleanup.
- Prefer existing lifecycle callbacks and narrow accessors. Do not suppress missing Mixin targets or silently ignore incompatible dependencies.
- Comments explain non-obvious constraints only. No change-log comments.

## Validation and releases

- `./gradlew check e2eJar` builds the production and test artifacts and runs unit/packaging checks.
- See `tests/e2e/README.md` for real-server invocations. A successful launch is not a passing regression test.
- Test the exact release JAR on every advertised Minecraft version. A second compilation is not evidence that the first JAR runs on that version.
- Cover rule off/on, both retention roots, active players, reconnects, direct disconnects and persistence. Keep observers out of the release artifact.
- Check both rules in all four combinations. BlueMap integration must load the actual pinned mod and inspect its player collections; event counts alone are insufficient. Test real clients with both rules enabled.
- After changing a feature, update its documentation and both language descriptions; keep README short.
- Before upgrading, inspect Carpet's logout callback, Fabric's `endSession`, and the three map-storage fields. Check whether upstream has fixed the underlying problem.
- Use `v<mod-version>` tags. Release only tested artifacts, with checksums and compatibility results; never rebuild between verification and upload.
- CI and release jobs do not deploy to the production server.

## Tools and commits

Use Context7 for library/API documentation and configuration. Use `uv` for Python execution and dependencies, and `pnpm` if Node tooling becomes necessary. Run environment-dependent tools natively in the WSL Linux build directory; for Windows-mounted Python/Node environments use the Windows tool via `cmd.exe /c`.

Before committing, ask for an issue number; the user may decline. Use conventional commits with a scope and an imperative subject of at most 50 characters. If supplied, append the issue closing reference. Keep commits atomic. Do not create or maintain a separate CLAUDE.md unless one exists; if present, keep it consistent with the project.
