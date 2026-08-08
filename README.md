# ludus

[![CI](https://github.com/LorenzoVicino/ludus/actions/workflows/ci.yml/badge.svg)](https://github.com/LorenzoVicino/ludus/actions/workflows/ci.yml)

A UCI chess engine written in Java, built in two acts: first a classical engine with a
hand-crafted evaluation, then an NNUE — a small neural network evaluation with an
incrementally updated accumulator — replacing it.

**Status: M0 complete.** Position representation, magic bitboard move generation, Zobrist hashing
and perft. The full perft suite passes — all 32 published counts across six positions, Kiwipete at
depth 5 and the initial position at depth 6 included. There is no search yet; that is M1.

The architecture, the measurement strategy and the milestone plan are in [`DESIGN.md`](DESIGN.md).

## Why two acts

Act I is a conventional engine: bitboards, magic bitboard move generation, alpha-beta search
with a transposition table, and an evaluation function written by hand.

Act II replaces that evaluation with an NNUE, the technique that gained Stockfish several
hundred Elo in 2020. The network is small, quantised to integers, and its first layer is
updated incrementally as moves are made and unmade rather than recomputed from scratch.

The point of splitting it this way is that the machine learning work has an **honest metric**.
Act II is not "a project that uses a neural network" — it is a measured Elo delta between two
versions of the same engine, established by an SPRT match. Most ML side projects report an
accuracy figure on a dataset that means nothing outside that dataset. This one reports whether
the engine actually got stronger.

The architecture is designed so Act II is a plug-in and not a rewrite: the evaluation sits
behind an interface with `onMakeMove` / `onUnmakeMove` hooks from day one, so the NNUE can
maintain its accumulator without the search ever knowing it exists.

## Correctness and strength are measured, not asserted

Two oracles do the work, and both exist before the code they validate:

**Perft** counts the leaves of the legal move tree at a given depth. The counts for standard
positions are published, so a mismatch is proof of a move generation bug — and per-move
counts at the root point at the branch containing it. It is a debugger, not just a test.

**SPRT** decides whether a change actually helped. Every search or evaluation patch plays a
match against the previous version under a sequential probability ratio test; if the test does
not pass, the patch does not land. One patch at a time, so a regression is always attributable.

A third invariant guards Act II: the incrementally updated accumulator must be bit-for-bit
identical to a full recomputation, verified by property tests over random games. A bug there
does not crash anything — it just quietly makes the engine play worse.

## Why Java

Serious engines are written in C++ or Rust, which makes the JVM the interesting part rather
than a handicap. Some of what the constraint forces:

- **Zero allocation in the search.** No objects in a loop that runs millions of times per
  second: moves are packed into `int`s, move lists are preallocated per ply, and the
  allocation rate during a long search is verified flat with JFR.
- **Transposition table as parallel primitive arrays.** An array of entry *objects* costs a
  second cache miss per lookup plus a header per entry; two `long[]` do not.
- **No `PEXT`.** BMI2's parallel bit extract is not portably reachable from Java, so sliding
  piece attacks use magic bitboards.
- **Vector API** (`jdk.incubator.vector`) for the NNUE accumulator, with a tested scalar
  fallback kept as the correctness reference.

## Roadmap

| | | Done when |
|---|---|---|
| **M0** ✅ | Board, magic bitboards, move generation | The full perft suite passes |
| **M1** | Search, evaluation, UCI | Plays a complete legal game against a GUI |
| **M2** | Quiescence, transposition table, move ordering | Beats M1 by SPRT — the Elo baseline |
| **M3** | Direct legal movegen, pruning, time management | Perft still correct, every patch SPRT-positive |
| **M4** | NNUE inference, first trained network | Accumulator invariant green, SPRT-positive vs M3 |
| **M5** | Vector API, tuning, `halfKP` features | Higher nps at equal Elo, then higher Elo |

## Building

JDK 24 is the only prerequisite — the Maven wrapper fetches Maven itself.

```bash
./mvnw verify          # build and the fast test suite: 60 tests, about 3 seconds
./mvnw test -Pslow     # the full perft suite: 32 cases, about 15 seconds
```

The split exists so the gating build stays quick. Deep perft counts roughly 600 million nodes, so
it runs nightly and on demand rather than on every push.

Current M0 measurements, from the deep suite:

| Position | Depth | Nodes | Nodes/second |
|---|---:|---:|---:|
| Initial | 6 | 119,060,324 | 36.6 M |
| Kiwipete | 5 | 193,690,690 | 53.8 M |
| Position 3 | 6 | 11,030,083 | 46.0 M |
| Position 4 | 5 | 15,833,292 | 49.8 M |
| Position 5 | 5 | 89,941,194 | 47.8 M |
| Position 6 | 5 | 164,075,551 | 55.2 M |

A node here means pseudo-legal generation, `makeMove`, a legality check and `unmakeMove`. This is
not search speed — there is no search yet — but it is the ceiling the search will be measured
against, and it is what the zero-allocation discipline buys.

Training scripts (Act II) will be a separate Python project under `training/`.

## License

MIT — see [`LICENSE`](LICENSE). All code is original.
