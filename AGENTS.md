# Repository Guidance

## Project conventions

- Keep source code, comments, documentation, commit messages, and workflow text in English.
- Do not install a Java runtime or other build tooling without explicit user approval.
- If a local Java runtime is needed, prefer an existing project-compatible runtime managed by `asdf`.
- Local compilation is intentionally optional for small changes. The GitHub Actions workflow is the expected build and packaging environment when no local Java development setup is available.
- Do not push changes unless the user explicitly asks. Leave reviewed changes in the worktree or create local commits only when requested.

## Build and release

- The root project uses Java 17, Gradle, and the IntelliJ Platform Gradle Plugin.
- Build and test the plugin with:

  ```sh
  ./gradlew --no-daemon clean check buildPlugin
  ```

- Packaged plugin ZIPs are written to `build/distributions/`.
- For a local build without host Java or Gradle, run `make`, `make build`, or `./scripts/build-plugin-docker.sh`. It uses Java 17 inside Docker, runs the same `clean check buildPlugin` tasks, and exports the ZIP plus `SHA256SUMS.txt` to `build/docker-distributions/` by default. Override the Make destination with `DOCKER_OUTPUT=/path/to/output`.
- The Docker build requires BuildKit/Buildx and uses a persistent cache mount for Gradle dependencies. Keep build output and local IDE/cache directories out of the Docker context via `.dockerignore`.
- `.github/workflows/build-plugin.yml` builds on pushes to `main` and via manual dispatch. It validates the Gradle wrapper, runs tests, creates SHA-256 checksums, uploads a workflow artifact, and publishes a GitHub Release.
- The workflow checks out the current branch revision; it is deliberately not pinned to a fixed source commit.
- Increment the version in `build.gradle.kts` for each installable hotfix so RubyMine can distinguish newly built ZIPs from an already installed version.

## Colored indentation guides

The original indent renderer only drew a colored guide when it could recover a `RainbowInfo` value from a matched bracket PSI element. That works for bracket-delimited constructs but not for ordinary Ruby blocks such as `class/end`, `def/end`, `if/end`, and `do/end`.

The Ruby support added in this fork lives primarily in:

- `base/src/main/kotlin/com/chylex/intellij/coloredbrackets/indents/RainbowIndentGuideRenderer.kt`
- `base/src/main/kotlin/com/chylex/intellij/coloredbrackets/indents/RainbowIndentsPass.kt`
- `base/src/main/kotlin/com/chylex/intellij/coloredbrackets/indents/RainbowIndentCaretListener.kt`

### Ruby block detection

- When no bracket-specific treatment is needed, Ruby guide colors are derived from indentation depth.
- Ruby block ranges are accepted when their effective closing line begins with one of:

  ```text
  end else elsif when rescue ensure
  ```

- Empty and comment-only lines inherit the boundary state of the preceding effective code line. This preserves outer blocks whose highlighter range ends at EOF or after a final newline without repeatedly scanning backward per guide.
- Boundary information is calculated once per highlighting pass and stored as metadata on each range highlighter. Keep Ruby language detection and document scanning out of the renderer's `paint` method.
- Ruby language detection uses the PSI language ID case-insensitively and does not introduce a compile-time dependency on Ruby PSI classes.
- Ruby block detection intentionally tolerates continued and aligned expressions before `do`, for example:

  ```ruby
  trait "long description" \
           "continued description" do
    work
  end
  ```

- Plain continuation indentation is not treated as a Ruby block merely because it is indented. Lines ending in `]`, `}`, `)`, or another ordinary expression do not satisfy the Ruby boundary fallback.
- Existing bracket-driven guide behavior remains intact. A multiline Ruby array may still receive its square-bracket guide color because `[` and `]` are real bracket delimiters.

### Color precedence

For a Ruby block boundary, derive the color from the current configured palette before consulting cached `RainbowInfo`. This ordering is important:

```text
current Ruby indentation palette
    -> cached bracket RainbowInfo
    -> do not draw a custom guide
```

Some Ruby PSI elements can retain previously calculated `RainbowInfo` colors. Preferring that cache caused stale white or old yellow guides after the color scheme changed. The old Darcula round-bracket level-zero default was `#E8BA36`, which was the source of the unexpected yellow guide.

### Active guide selection

Do not depend on IntelliJ's `caretIndentGuide` to select the active custom guide. In RubyMine it may only identify a guide when the caret is positioned on or near the guide column.

`RainbowIndentsPass` selects and caches the active guide directly:

1. Read the plugin-owned range highlighters for the editor.
2. Keep valid ranges containing the caret offset.
3. Select the innermost range by latest start offset and, for equal starts, earliest end offset.
4. Cache the result for the current caret offset and highlighter list.
5. Render only that guide at full color.

This makes the current block guide active when the caret is anywhere inside the block, including on an empty line or to the right of the indentation column.

`RainbowIndentCaretListener` invalidates the active-guide cache on explicit caret movement and repaints the editor only when the active guide changes. The cache also compares the current caret offset so document-induced caret movement during typing cannot leave it stale. Do not restore a full highlighter-list scan for every guide in `paint`; that turns a repaint into quadratic work and causes visibly progressive guide updates.

Document changes still invalidate the normal indentation highlighting pass. This is required to keep guide ranges correct, and IntelliJ schedules that work as a cancellable background highlighting pass. Ruby-specific boundary classification must remain a single linear scan per pass; it must not be repeated per guide or per painted segment.

### Guide colors

Ruby block guides use the Round Brackets palette because Ruby keyword blocks do not have a natural bracket type. Bracket-backed guides retain the palette of their delimiter:

- `(...)`: Round Brackets
- `[...]`: Square Brackets
- `{...}`: Squiggly Brackets
- `<...>`: Angle Brackets

The first five defaults for every bracket type, in both bundled light and Darcula schemes, are:

1. `#FF5EA8` — neon pink
2. `#A78BFA` — soft violet
3. `#4DA6FF` — electric blue
4. `#22D3EE` — bright cyan
5. `#34D399` — neon mint

The default number of active colors is five, so deeper levels cycle through this palette.

- The active guide is drawn at 100% of its configured color.
- Inactive guides use `alphaBlend(defaultBackground, 0.3f)`.
- User-specific `.icls` color-scheme overrides take precedence over bundled defaults and may need to be reset manually to observe new defaults.

## Validation expectations

- Always run `git diff --check` after edits.
- Review the complete diff and keep unrelated user changes intact.
- When working without an approved local Java environment, explicitly report that compilation and tests were not run locally and rely on the GitHub Actions result for build verification.
- Useful manual Ruby cases include nested `class`, `def`, `if`, and `do` blocks; empty lines inside the active block; EOF after the final `end`; trailing blank or comment lines; multiline arrays and argument lists; and backslash-aligned continuations before `do`.
