# Material 3 Expressive dimension policy

The adaptive app shell and destination screens use semantic dimensions from
`SpacingTokens`, `SizeTokens`, and `ElevationTokens`. Literal `dp` measurements are
not permitted in `MainActivity.kt` or `*Screen.kt` files
unless a framework/API contract cannot be represented honestly by a reusable token.

## Token taxonomy

- `SpacingTokens` describes layout rhythm: zero/micro insets, compact and standard
  gaps, row padding, card padding, and section separation.
- `SizeTokens` describes component meaning: icon and loading sizes, minimum touch
  targets, content widths, editor/dialog bounds, and adaptive breakpoints.
- `ElevationTokens` describes the Material surface hierarchy.

Tokens are named for their UI role rather than being a numeric dump. A new literal
that is used more than once, participates in adaptive layout, affects a touch target,
or expresses a component size must become an appropriately named shared token.

## Reviewed exceptions

The current exception set is empty: the adaptive shell and all 10 destination screens
have zero literal `dp` measurements. If a genuine framework/API exception is introduced, add one TSV
record to `app/src/test/resources/design-token-dp-exceptions.tsv`:

```text
relative_file<TAB>literal<TAB>expected_count<TAB>trimmed_source_line<TAB>reason
```

Each exception is bound to its file, literal, exact trimmed source line, count, and a
non-empty justification. `ScreenDesignTokenPolicyTest` compares the complete observed
set with the allowlist, so a new, moved, duplicated, or silently changed literal fails
the unit-test gate until it is removed or explicitly reviewed.
