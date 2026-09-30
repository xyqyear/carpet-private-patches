# Real Minecraft regressions

Run local tests in **WSL**, from a native Linux build directory, with Java 25, `uv` and the Gradle Wrapper. CI uses Ubuntu 24.04.

```bash
./gradlew check e2eJar
mkdir -p build/release-candidate
cp build/libs/*+mc26.2-26.3.jar build/release-candidate/
EULA=true uv run --locked --project tests/e2e tests/e2e/run.py \
  --mc 26.2 --profile minimal --cycles 1000 \
  --jar build/release-candidate/carpet-private-patches-0.1.1+mc26.2-26.3.jar
```

Set `EULA=true` after accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA). The runner downloads hash-checked fixtures and uses isolated worlds bound to localhost, offline login, small view distances and the official Fabric launcher.

For Minecraft 26.3, compile the **test Mod** against that version, then pass the same frozen production JAR:

```bash
./gradlew e2eJar -PmcVersion=26.3
EULA=true uv run --locked --project tests/e2e tests/e2e/run.py \
  --mc 26.3 --profile lithium --cycles 1000 \
  --jar build/release-candidate/carpet-private-patches-0.1.1+mc26.2-26.3.jar
```

Required server matrix: `minimal` and `lithium` on both versions, plus `production-mods` on 26.2. The latter pins the incident's Carpet/API/Lithium/TIS/Igny combination; it is not a complete copy of the production server.

The harness constructs genuine Carpet players through `respawnFake` and `placeNewPlayer`, avoiding external profile lookups. It alternates the actual `/player ... kill` command and direct listener disconnect. It executes `tickCarriedBy` with a real `ItemFrame` instance to exercise Lithium's framed-map path, and separately keeps a dormant map. This tests the affected map method and references, not rendering or item-frame gameplay.

With Igny installed, it also starts the real `VaultTask`, lets its logout stage run, and tests its stop cleanup. Reflection exists only in this optional test adapter; production code has no Igny dependency. The negative control deliberately reproduces leaks before test-only cleanup isolates later assertions.

## Real client

Install Xvfb and Mesa/OpenAL runtime libraries, then run:

```bash
EULA=true uv run --locked --project tests/e2e tests/e2e/run.py \
  --mc 26.2 --profile minimal --client \
  --jar build/release-candidate/carpet-private-patches-0.1.1+mc26.2-26.3.jar
```

Repeat for 26.3. Assets are prepared before the dedicated server starts. Fabric client GameTest drives a real client through three network connections, two dimension changes and a respawn. The server runs in a separate JVM, and the production patch is absent from the client. Xvfb and Mesa software rendering keep the tests independent of WSL's host GPU driver.

Include `libegl1` and `libegl-mesa0` in the Linux runtime libraries. The runner sets [`SDL_VIDEO_FORCE_EGL=1`](https://wiki.libsdl.org/SDL3/SDL_HINT_VIDEO_FORCE_EGL): Minecraft 26.3 requests an sRGB framebuffer that Xvfb's GLX path may not provide. A failed server report also stops the client promptly.

## Results

`build/e2e/<version>-<profile>/result.json` records the production JAR hash, pinned dependencies and completed checks. Per-run directories contain server/client logs, JSON details and JUnit XML. Client profiles use the `-client` suffix.

A zero exit code alone is insufficient: missing reports, zero checks, failed assertions, crashes and timeouts fail the runner. Old unsuccessful run directories are preserved for diagnosis. Release collection requires five 1,000-cycle server results and two real-client results, all for the same JAR.

```bash
uv run --locked --project tests/e2e tests/e2e/collect_results.py \
  --candidate build/release-candidate --results build/e2e --output build/dist
```

Test classes, observation accessors and test commands must stay outside the production JAR.
