# The informant-register parity pack, in tree

A byte-identical copy of
`analysis/results-distribution/InformantRegister/parity-pack/`, taken at oracle
digest `5f39b242bcbf9845893414c5af0d61d5c60cf142a8584af0387cc04e12f844b8` against
Node app commit `a8d3c00b92c3d4cc5da5a555699a63d30adcf8db` (clean tree, Node
v24.18.1). Every `expected.json` here is a recording of the **real** function app,
not an expectation anyone wrote.

| Directory | Contents | Read by |
|---|---|---|
| `recorded/` | 384 differential cases — inputs, expected output, provenance | `RegisterTransformerParityTest` |
| `pinning/` | 26 oddity entries plus `manifest.json` | `PinnedOddityTest` |
| `../comparator-vectors/vectors.json` | 57 adversarial comparator vectors | `ComparatorContractTest` |

## What was left out, and why

Nothing was truncated. `diff -r` against the source pack is clean apart from two
files, both deliberately excluded:

| Not copied | Why |
|---|---|
| `pinning/build-pinning.js` | Node harness that *regenerates* the pinned cases by driving the oracle against the function app's working tree. It is not test data, it cannot run from this repo, and copying it would invite someone to regenerate an expectation to make a Java test pass — which the pack forbids. |
| `pinning/entries.js` | The catalogue the generator reads. Its content is already in `pinning/manifest.json`, which is copied in full and is what the JUnit tests read. |

`oracle/`, `fixtures/` and `coverage/` are not copied either: `oracle/` is the Node
CLI itself, `fixtures/` is the input the corpus was *built from* (every case
carries the resulting payload under its own `inputs/`), and `coverage/` is
analysis. All three stay in the analysis tree, which is where a rebuild happens.

## The rule

**Never regenerate an `expected.json` to make a Java test pass.** Regeneration is
only ever a response to the Node source changing, and the Node app is frozen. A
rebuild that changes an expectation while the provenance fields stay the same is a
genuine non-determinism and must be investigated, not re-recorded.

A pinned behaviour changes only in a commit that also adds the corresponding row to
`doc/DEVIATIONS.md`.
