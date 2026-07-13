# Colored Brackets

This is a fork of the [🌈Rainbow Brackets](https://github.com/izhangzhihao/intellij-rainbow-brackets) plugin by [izhangzhihao](https://github.com/izhangzhihao), based on version 6.26.

## Key Changes

- Support for C# (Rider)
- Support for C++ (Rider, CLion, CLion Nova)
- Support for Settings Sync
- Improved highlighting performance
- Increased default setting for maximum line count from 1K to 100K
- Fixed service initialization warnings reported by 2024.2+
- Colored indentation guides for Ruby and YAML blocks
- Highlight the current indentation guide while the caret is anywhere inside its block

## Build locally with Docker

The Docker build requires Docker with Buildx, but no local Java or Gradle installation. It runs the test suite, builds the plugin package, and creates a SHA-256 checksum:

```sh
./scripts/build-plugin-docker.sh
```

Alternatively, use Make:

```sh
make
# or: make build
```

The plugin ZIP and `SHA256SUMS.txt` are written to `build/docker-distributions/`. Pass a directory as the first argument to use a different output location:

```sh
./scripts/build-plugin-docker.sh /path/to/output
```

With Make, override `DOCKER_OUTPUT` instead:

```sh
make build DOCKER_OUTPUT=/path/to/output
```

The first build downloads the Java base image, Gradle distribution, IntelliJ Platform, and project dependencies. Later builds reuse Docker's persistent Gradle cache.
