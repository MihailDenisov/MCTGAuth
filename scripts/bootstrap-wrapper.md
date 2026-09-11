# Gradle Wrapper Bootstrap

The binary Gradle wrapper JAR is intentionally not fabricated in this scaffold. Once Gradle is available locally, generate a pinned wrapper from the repository root, for example:

```bash
gradle wrapper --gradle-version <PINNED_VERSION>
```

Then commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`. Choose the Gradle version required by the NeoForge 1.21.1 MDK/plugin version you pin.
