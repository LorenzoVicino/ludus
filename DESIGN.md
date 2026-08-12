# ludus — Design Document

A UCI chess engine in Java, in two acts: a hand-written evaluation, then an NNUE.

> **What this document is.** A working design record, not a specification written after the fact.
> Where the implementation departed from the plan, the departure is recorded next to the original
> reasoning rather than edited out. Those notes are the most useful thing in here: a design document
> that stops tracking reality stops being worth reading.

---

## 0. Goals

**What has to be true at the end.**

1. The engine is **correct**: exact perft across the standard suite.
2. The engine is **strong**: Elo measured, not eyeballed.
3. **Act II is a graft, not a rewrite**: replacing the hand-written evaluation with the NNUE must not
   touch a single file under `search`.
4. Every improvement is **backed by a number**, never by an impression.

**Declared non-goals**, as a defence against the scope creep that is the standard way projects like
this die:

| Out of scope | Why |
|---|---|
| A graphical interface of our own | UCI gives you Arena, Cute Chess and En Croissant for free |
| Parallel search (Lazy SMP) | Doubles the difficulty of debugging the search. After Act II, if ever |
| Syzygy tablebases | Easy Elo, no interesting content |
| Opening book | Likewise |
| Pondering | Complicates time management and teaches nothing |

---

## 1. Metrics and oracles

This section comes **before** the architecture, deliberately. The oracles are the reason this project
was chosen at all, and they get built first rather than bolted on afterwards.

### 1.1 Perft — the correctness oracle

`perft(n)` counts the leaves of the legal move tree at depth `n`. The counts are published for
standard positions, so a mismatch is proof of a bug — and **divide** (perft per root move) tells you
which branch to look in. It is a move generation debugger, not merely a test.

| Position | d1 | d2 | d3 | d4 | d5 | d6 |
|---|---|---|---|---|---|---|
| Initial | 20 | 400 | 8,902 | 197,281 | 4,865,609 | 119,060,324 |
| Kiwipete | 48 | 2,039 | 97,862 | 4,085,603 | 193,690,690 | — |
| Position 3 | 14 | 191 | 2,812 | 43,238 | 674,624 | 11,030,083 |
| Position 4 | 6 | 264 | 9,467 | 422,333 | 15,833,292 | — |
| Position 5 | 44 | 1,486 | 62,379 | 2,103,487 | 89,941,194 | — |
| Position 6 | 46 | 2,079 | 89,890 | 3,894,594 | — | — |

> **Check these against the Chess Programming Wiki before encoding them in tests.** They are the
> standard values, but a test that asserts the wrong number is worse than no test: it sends you
> hunting a bug that does not exist, or blesses one that does.

The suite lives in `perft-suite.txt` as `FEN;d1 n1;d2 n2;…`, so it is data rather than code. It sits
in **main** resources, not test resources, because two consumers read it: the tests and the status
page generator. A second transcription of thirty-two hand-checked numbers is exactly the mistake the
warning above describes.

### 1.2 Elo — the strength oracle

No change to search or evaluation lands without a match.

- Opponent: the previous version of `ludus` itself, as a separate jar.
- Openings played **twice with the colours swapped** — without that, a match measures the opening
  book as much as the engines, because a position that mildly favours White hands free points to
  whoever drew White more often.
- **SPRT** with `elo0=0, elo1=10, alpha=beta=0.05`: it stops on its own once it has an answer,
  instead of making you play twenty thousand games for nothing.

The hard rule: *if the SPRT does not pass, the patch does not land*, however elegant the code. This is
the discipline separating an engine that improves from one that merely grows.

**Deviation on the tool.** The plan named **fastchess** (or `cutechess-cli`). The match runner is
instead inside the repository, in `ludus-tools`, for two reasons — one accidental, one that holds
regardless.

The accidental one: on the development machine, **Norton quarantines `fastchess.exe`**. It is an
unsigned downloaded executable that spawns subprocesses, and behavioural protection removed it from
disk midway through the first match. It did the same to an engine jar copied into the temporary
directory. Disabling somebody else's antivirus is not an acceptable workaround.

The one that holds regardless: an in-repository harness **is usable from CI**. From M3 onwards every
patch must pass an SPRT, and a gate that depends on downloading an external binary is a fragile gate.
The runner reports its verdict as an **exit code** — 0 accepted, 1 rejected, 2 inconclusive — which is
exactly what a workflow needs in order to block a patch without a human reading the output.

The cost is having written the statistics by hand, and that is where the tests go: `SprtTest` and
`EloEstimateTest` check the known anchor points of the Elo scale (400 points is ten to one), that the
likelihood ratio has the right sign, that a four-game match decides nothing, and that a zero variance
is not read as infinite evidence.

On time control: the runner gives a **fixed allowance per move** rather than running a clock. That is
a choice, not a shortcut — it separates *what the search does with the time it gets* from *how well a
version divides a clock*. Two different questions, and a real clock would measure them together.

On the LLR: it is the normal-approximation form, with the variance taken from the **observed**
win/draw/loss split rather than assumed. That matters because the split is very different for two
engines a hundred Elo apart and two engines five apart, and assuming it would make the test
optimistic in exactly the case that calls for caution.

### 1.2.1 Running a match across machines

A match is 300 to 500 games and its wall time is what limits how fast patches can be evaluated. The
runner therefore has a second mode: a coordinator hands out openings over **RabbitMQ**, and any
number of workers on any number of machines play them.

**This is the only third-party runtime dependency in the project, and it is confined to
`ludus-tools`.** That module is development tooling and never ships inside the jar a GUI launches, so
the engine keeps its zero-dependency property. A broker connection inside the engine would be
indefensible: it is a subprocess that must start in milliseconds and must not allocate in its hot
loop.

The unit of work is an **opening pair**, not a game, because the colour swap is what cancels the
opening's bias — splitting a pair across machines would make a half-result meaningless, and losing
half a pair is exactly what happens when a worker dies.

What the broker is actually being relied on for, rather than used to decorate:

| | |
|---|---|
| **Manual acknowledgement** | Nothing is settled on receipt. A job is acknowledged only after its games are played *and* the result is published, so a machine that dies mid-job hands the work back instead of losing it |
| **Prefetch** | The backpressure. A job is minutes of work, so a worker takes as many as it has threads and no more — otherwise the first machine to connect claims the whole match and the others idle |
| **Publisher confirms** | A dropped result is minutes of CPU gone and, worse, a tally that silently disagrees with the games actually played |
| **Durable queues, persistent messages** | A broker restart must not discard a match in progress |
| **Dead-letter queue** | A job that cannot be played stops cycling through workers and stays somewhere it can be looked at |

**Verified rather than assumed.** With a worker holding a job, the queue reads `3 ready, 1
unacknowledged`. Kill the worker process and its engines outright, and it reads `4 ready, 0
unacknowledged` — the job came back on its own. That is the whole reason for putting a broker in the
middle, so it is checked with a real `kill` rather than trusted from the documentation.

The transport sits behind an interface with an in-memory implementation, for the same reason the
engine has one in front of its evaluation: the coordination logic is worth testing on its own, and a
test that needs a running broker does not run in CI.

**What this does not fix.** Throughput, not resumability. An inconclusive match still has to be
replayed from the start — the limitation recorded in §9.0 stands, and distributing the work only
makes each attempt faster.

**Two things that cost an hour and are worth writing down.** The default virtual host is named
`/`, and in a URI that slash must be percent-encoded: ending the URI with a bare `/` asks for a
virtual host named `""` and the broker answers `NOT_ALLOWED - vhost  not found`, where the only clue
is the double space. And the shaded jar needs a services transformer, or SLF4J's ServiceLoader
binding is overwritten and every broker diagnostic disappears into a no-op logger at precisely the
moment something has gone wrong.

### 1.3 nps — the performance oracle

The purpose is to separate the two ways a patch can fail: *searching worse* (same nps, Elo down)
against *searching slower* (nps down). Different bugs, and without this metric they look identical.

Built: `ludus-match bench` searches a fixed set of eight positions to a fixed depth and reports the
node rate. Fixed depth rather than fixed time, deliberately — fixed time would hide exactly what is
being measured, since a slower engine would simply search less and report the same rate.

It earned itself immediately. The first trained network lost its match by 589 Elo, and the obvious
reading was that it needed more data. The benchmark said otherwise: nineteen times slower than the
hand-crafted evaluation, which is two to three plies of depth given away before the network's opinion
matters at all. See §9.05.

**M0 baseline**, from the deep perft run: **36–55 million nodes per second**, where a node covers
pseudo-legal generation, `makeMove`, a legality check and `unmakeMove`. Roughly 610 million nodes in
13 seconds across all 32 cases. It is not search nps — there was no search yet — but it is the
ceiling the search gets measured against, and it confirms the zero-allocation discipline of §3.3 is
paying.

### 1.4 The accumulator invariant — the Act II oracle

The NNUE's incremental update must produce **exactly** the same result as a full recomputation, bit
for bit. That is a test, not a hope. Details in §7.4.

---

## 2. Architecture

### 2.1 Modules

Maven multi-module, JDK 24.

> **Deviations from the first draft.** The build was planned in Gradle; it is Maven, by explicit
> choice. The target was JDK 25 LTS; it is 24, the version installed on the development machine.
> Neither changes anything substantial — the Vector API of Act II is in incubator on both.

```
ludus/
├── ludus-core/      board, move gen, zobrist, FEN, move encoding, SEE
├── ludus-eval/      Evaluator interface + HCE (hand-crafted evaluation)
├── ludus-search/    alpha-beta, transposition table, ordering, time management
├── ludus-nnue/      NNUE implementation of Evaluator            [Act II]
├── ludus-uci/       UCI protocol, entry point, shaded jar
└── ludus-tools/     match runner, SPRT statistics, status page generator
```

**Why multi-module rather than one project with packages.** Not ceremony: the modules have *genuinely
different* dependencies. `ludus-nnue` will need `--add-modules jdk.incubator.vector`; `ludus-tools`
pulls in tooling that must not end up in the jar handed to Cute Chess. Above all, the dependency
graph makes the Act II seam **impossible to violate by accident**: `ludus-search` depends on
`ludus-eval` and does not know `ludus-nnue` exists. The day somebody is tempted to call network code
from the search, the compiler stops them. Packages would not.

The graph is acyclic and one-way:

```
uci ──► search ──► eval ──► core
 │                   ▲
 └────► nnue ────────┘   (nnue implements eval; search never learns of it)
```

`ludus-uci` is the only place that knows which `Evaluator` to instantiate. It is the composition root.

### 2.2 Base package

`io.github.lorenzovicino.ludus.*` — the correct reverse domain for a project hosted on GitHub Pages.

---

## 3. Position representation

### 3.1 Bitboards

```java
long[] byType   = new long[6];  // PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING
long[] byColor  = new long[2];  // WHITE, BLACK
long   occupied;                // derived, kept so it is not recomputed
```

**Six plus two rather than twelve separate bitboards.** Less cache occupied (8 longs against 12) at
the price of one extra `AND` to ask for "white knights". The search is dominated by cache misses, not
by ALU work, so the trade runs in the right direction.

Additional state:

```java
int  sideToMove;
int  castlingRights;   // 4 bits: WK, WQ, BK, BQ
int  epSquare;         // -1 when absent
int  halfmoveClock;    // for the fifty-move rule
long zobrist;          // maintained incrementally
```

### 3.2 Zobrist hashing

Random keys from a **fixed, hardcoded seed**: reproducibility is indispensable for debugging, and a
bug that depends on different keys each run is a nightmare.

Updated incrementally in `makeMove`/`unmakeMove`. The components to remember: piece×square, castling
rights, en passant square, side to move. The two classic omissions are rights that lapse and an en
passant square that expires — both produce subtle table collisions that surface as an absurd move
once every ten thousand games.

### 3.3 Make/unmake, not copy-make

`Board` is **mutable**, with a stack of undo information: captured piece, previous castling rights,
previous en passant square, previous halfmove clock, previous hash.

**Why not copy the position.** Copy-make is simpler and immune to a whole class of bug, but it
allocates at every node — in a loop that runs ten million times a second. Even with TLABs and a
generational collector, that is the kind of code that spends its time in the allocator instead of
searching. Make/unmake is the right choice, and §8.2 describes the test that makes it safe.

**Zero-allocation discipline in the search.** Non-negotiable for code under `search` and `eval`:

- No `new` on the hot path. Ever.
- Move lists are preallocated `int[]`, one per ply, in a two-dimensional array indexed by depth.
- No `Move` objects, no `Optional`, no streams, no boxing. No capturing lambdas.
- Verify rather than trust: profile with JFR and confirm the allocation rate during a long search is
  **flat**. If it is not, there is a hidden `new` and it needs finding.

This is one of the more interesting things to write about: *what changes when you write
high-performance code on the JVM instead of in C++*. Original content, not a summary of the wiki.

### 3.4 Move encoding

A move is an **`int`**, not an object:

```
bits  0-5   from square
bits  6-11  to square
bits 12-15  flags
```

The four flag bits follow the conventional encoding: quiet, double pawn push, kingside castle,
queenside castle, capture, en passant capture, and the four promotions × {capturing, not}. Two of the
bits carry meaning directly — bit 2 marks a capture and bit 3 a promotion — so both questions are
answered without a lookup table, and the promoted type is `KNIGHT + (flags & 3)`.

Zero is not a valid move, since `from == to == 0`, so it doubles as the "no move" sentinel without
needing a special value.

---

## 4. Move generation

### 4.1 Attacks

- **Knight, king, pawn**: precomputed `long[64]` tables. Trivial.
- **Bishop, rook, queen**: **magic bitboards**, indexed by `(occupancy & mask) * magic >>> shift`.

A note for anyone arriving from C++: BMI2's `PEXT`, the modern and simpler route, is **not reachable
from Java** portably. Magics are the only road.

**Deviation, and the reasoning behind it.** This section originally said to use published magic
numbers rather than searching for them at runtime. The implementation searches, with a fixed seed,
because a wrong magic **does not fail loudly**: it silently returns the correct attack set for the
wrong occupancy, and the symptom emerges as an impossible move a thousand nodes later. Transcribing
128 sixty-four-bit constants by hand is exactly the task where one wrong digit survives review.

A search cannot make that mistake, because it **verifies** each candidate against the reference
implementation for every occupancy before accepting it. The tables are correct by construction rather
than by transcription. The cost is tens of milliseconds at startup, paid once; if it ever matters, the
found magics can be printed and pinned. Fixed seeds make the search deterministic, so every run of
every build produces byte-identical tables.

It is the principle of §4.2 applied one level down: the slow, obviously correct code exists to
validate the fast code.

### 4.2 A two-stage strategy, deliberately

**Stage one — pseudo-legal plus a filter.** Generate every move ignoring checks, then for each one
`makeMove`, ask whether your own king is attacked, and `unmakeMove`. Slow, and **very hard to get
wrong**.

**Stage two — direct legal generation.** Pin masks, a check mask, double check handled separately.
Much faster, much easier to get wrong.

**Why this order.** Stage one gives a correct perft in half a day, and that correct perft becomes
**the oracle against which stage two is validated**. Starting from legal generation leaves nothing to
compare against, and a week hunting a discrepancy at depth 5. This is the single most important piece
of advice in the document: *the purpose of the slow code is to validate the fast code.*

Stage two has not been written yet. See §9.0 for why it moved to M5.

### 4.3 The cases that break everybody

Covered by dedicated unit tests as well as by perft:

- Castling: destination attacked, transit square attacked, king already in check, rook already moved,
  rook captured on its home square.
- **En passant that uncovers a check** along the fifth rank — the classic perft killer, because the
  capture removes *two* pawns from different ranks at once.
- En passant that would be legal but the capturing pawn is pinned.
- Capturing promotions, and underpromotion — an engine that always promotes to a queen throws away
  games to stalemate.
- Double check: king moves only, no interposition and no capture of a checker.

---

## 5. Search

### 5.1 Structure

Negamax alpha-beta with **PVS** (principal variation search), inside **iterative deepening**.

Iterative deepening is not wasted work despite re-searching: the search at depth `d-1` fills the
transposition table and produces the ordering that makes depth `d` dramatically faster. It is also
what makes time management possible — you can stop partway and still have a good move.

Aspiration windows are **planned, not implemented**.

### 5.2 Quiescence search

At depth zero, do not evaluate immediately: keep searching captures and promotions until the position
is quiet. Without it the engine suffers the *horizon effect* and is tactically blind — the single
piece worth more Elo than any other.

Includes stand pat (if the static score already beats beta, cut) and **SEE pruning**: a capture that
loses material cannot improve a quiet position, and searching it invites an endless chain of bad
trades. Evasions are exempt, since a side in check may have no better option.

Delta pruning, named in the first draft, was not implemented; SEE pruning does the same job here.

### 5.3 Transposition table

The layout is a Java-specific choice worth explaining:

```java
long[] keys;   // the full Zobrist key
long[] data;   // move | score | depth | bound | age, packed
```

**Two parallel arrays of primitives, not an array of `TTEntry` objects.** A `TTEntry[]` in Java is an
array of *references*: every lookup costs two cache misses instead of one, plus 16 bytes of header per
entry. On a 256 MB table the difference is enormous, on the hottest lookup in the engine. This is
exactly the kind of detail Java's memory layout forces you to consider and that a C++ `struct` gives
you for free.

Points of care:

- Power-of-two size, indexed by masking. Configurable through UCI `setoption name Hash`.
- Replacement policy: prefer greater depth, with an **age** so entries from earlier searches give way.
  Note what is deliberately absent — a clause letting a matching key always win. It is the obvious
  thing to write and it is wrong: a depth-2 result would evict a depth-10 answer for the same
  position. Depth decides, whatever the key says. A test catches this.
- **Mate scores relative to the node.** A mate stored as "mate in 3 from here" and read at a different
  ply becomes a wrong score. Convert on the way in and back on the way out. This is a classic bug and
  it surfaces as an engine announcing mates that do not exist.
- One entry is not enough for repetition: a draw by repetition depends on the *path*, not the
  position. A separate stack of hashes is required, and it is consulted **before** the table.

### 5.4 Move ordering

Ordering is worth more than almost any other optimisation: ideal alpha-beta visits `√N` nodes instead
of `N`, but only if the best moves come first.

Order: table move → good captures by SEE → promotions → killer moves → history heuristic → the rest.

**SEE** (static exchange evaluation) earns its own clean implementation and its own unit tests: it
serves both ordering and quiescence pruning, and it is subtle code.

### 5.5 Pruning and reductions — one at a time

**Landed and measured:** null move pruning, late move reductions. **Next:** futility pruning, reverse
futility — reverse futility is written and waiting for its match on the `search-reverse-futility`
branch, because an unmeasured patch has no business on `main`.

**The rule: one patch at a time, each with its own SPRT.** Add three together and watch the Elo drop,
and you do not know which one did it. This is where the discipline of §1.2 repays the cost of having
built it — see §9.0, where the very first patch measured at nothing and the reason was worth more than
the patch would have been.

### 5.6 Time management

From `go wtime btime winc binc movestogo`: compute a *soft* budget, past which no new iteration starts,
and a *hard* one, past which the search aborts outright. Check the clock every few thousand nodes
rather than at every node — `System.nanoTime()` in a hot loop costs more than you would think.

### 5.7 A note on `Evaluator` polymorphism

`Evaluator` is an interface called millions of times per second, which raises a fair question in Java:
**does the call site become megamorphic and stop being inlined?**

No, and the reason is worth understanding. A single run of the engine loads exactly *one*
implementation — HCE or NNUE, chosen at startup. The JIT sees a monomorphic call site and inlines
normally. The risk exists only if both were loaded in one process, which happens in exactly one place:
comparative tests, where inlining does not matter.

Prevention: `final` on the implementation classes, and one pass with `-XX:+PrintInlining` to confirm
inlining actually happens. Verifying instead of assuming is the whole point.

---

## 6. The evaluation seam

**This is the most important architectural decision in the document.** It is what makes Act II a graft
rather than a rewrite, and it has to be taken on day one, when it is not yet useful for anything.

```java
public interface Evaluator {

    /** Centipawns from the point of view of the side to move. */
    int evaluate(Board board);

    /** Called with the board in its PRE-move position, before the move is applied. */
    default void beforeMakeMove(Board board, int move) {}

    /** Called with the board back in its PRE-move position, after the move was undone. */
    default void afterUnmakeMove(Board board, int move) {}

    /** Discards incremental state and rebuilds it from the board. */
    default void reset(Board board) {}
}
```

**Deviation, found while implementing.** The first draft had the make hook firing **after** the move
was applied. That does not work: once the move is on the board the captured piece is **gone**, and its
identity and square are exactly what a feature delta needs. Reading the position *before* makes every
piece involved plainly visible — the mover, the victim, the castling rook — without asking anything
more of `Board`.

The names changed accordingly. `beforeMakeMove` and `afterUnmakeMove` **carry the contract**, instead
of entrusting it to a comment somebody has to remember. Both see the pre-move position, so the pair is
symmetric.

`reset` was added for a practical reason that surfaced with UCI: a host can hand the engine a position
unrelated to the previous one, and at that point an incremental accumulator must be rebuilt from
scratch.

One last detail the implementation settled: the search calls the hooks around **every** move it tries,
including ones that turn out illegal and are immediately unmade. The pairing is therefore always
balanced, which is what an implementation pushing and popping a stack relies on. Null moves do not
call them — the accumulator is per-perspective and a side-to-move flip changes nothing, since the
perspectives are concatenated in side-to-move order at evaluation time.

The `default` hooks are the whole game. `HandCraftedEvaluator` ignores them; it holds no state and
derives everything from the position in front of it. `NnueEvaluator` will use them to keep its
accumulator up to date.

Without them, Act II would mean editing `makeMove` and every branch of the search. With them,
`ludus-uci` changes one line:

```java
Evaluator eval = nnuePath != null
    ? new NnueEvaluator(NnueNetwork.load(nnuePath))
    : new HandCraftedEvaluator();
```

On patterns, honestly: it is **Strategy**, and the hooks have a flavour of **Observer**. That is not a
discovery and should not be sold as one. What is worth writing down is *why the boundary sits exactly
there* and not two layers up or down. A competent reader appreciates the reasoning about the boundary;
they already know the label.

### 6.1 HCE — the hand-crafted evaluation

Deliberately modest, because it is destined for the bin: material, piece-square tables interpolated
between midgame and endgame, pawn structure (doubled, isolated, passed), bishop pair.

**What actually landed in M1, and what did not.** Material, tapered piece-square tables, pawn
structure and the bishop pair are there. **Mobility and king safety are not**, which is consistent
with the paragraph below: they are the two most expensive terms to write and to tune, and on material
headed for the bin they are not worth the price.

A further declared simplification: only the **pawn and the king** have separate midgame and endgame
tables. Knights, bishops, rooks and queens share one across both phases, because their best squares
barely move — whereas for the pawn and the king the phase genuinely changes the answer, and that is
where interpolation earns its keep. Inventing a second set of untuned numbers would have added code
without adding information.

The test holding all of it together is **colour symmetry**: mirroring a position — flipping the ranks,
swapping the colours, handing over the move — must produce the same score. One property, and it
catches the two mistakes that are easiest to make and hardest to notice: a sign flipped in one term,
and a piece-square table indexed without mirroring for Black. Both leave the engine quietly convinced
that one colour is better.

**Do not spend weeks tuning HCE.** It serves two purposes: giving Act I an honest opponent, and
establishing the *Elo baseline* the NNUE will be measured against. Over-tuning it only makes the Act
II number less impressive.

---

## 7. Act II — NNUE

### 7.1 Network architecture

```
input: 768 features (64 squares × 6 types × 2 colours), sparse
   │
   ├── white perspective ──► feature transformer  768 → 256
   └── black perspective ──► (same weights)       768 → 256
                                    │
                    concatenated in side-to-move order → 512
                                    │
                              clipped ReLU
                                    │
                               512 → 32 → 32 → 1
```

**Why 768 features and not `halfKP`.** Stockfish's `halfKP` has roughly 41,000 features (king position
× piece × square) and performs far better, but it needs much more data to train and much more care. A
dense 768-input is the right choice for a first network: it trains on modest data, it works, and it
gives you the number. `halfKP` is the obvious next iteration, and it is better held as a *measured
improvement* than as an opening risk.

The **side-to-move ordered concatenation** is what makes the network symmetric: it learns "the side to
move is better off", not "White is better off".

### 7.2 The incremental accumulator

This is the heart of the acronym: the **E** in NNUE stands for *efficiently updatable*, and the
efficiency is all here.

The feature transformer is the expensive part (768 → 256, twice). But between a node and its child
**very few features change**: an ordinary move removes the piece from its origin and adds it at its
destination. Two columns of weights out of 768. So you do not recompute — you add and subtract.

```
ordinary move   →  −(piece, from)  +(piece, to)
capture         →  … and −(captured piece, to)
promotion       →  −(pawn, from)   +(promoted piece, to)
castling        →  four updates (king and rook)
en passant      →  −(captured pawn, the square that is NOT the destination)   ← careful
king move       →  full recomputation of that perspective, if using halfKP
```

Implementation: a stack of accumulators indexed by ply. `beforeMakeMove` copies from the level below
and applies the delta; `afterUnmakeMove` simply decrements — undoing is free, which is half the reason
the scheme works.

En passant is again the case that breaks everything: the captured pawn **is not on the destination
square**, and that is the mistake you will make.

### 7.3 Quantisation

Inference runs on integers, not floats. That is what makes it fast enough to sit inside a search.

- Feature transformer weights and accumulator: **int16**.
- Clipped ReLU: saturates to `[0, 127]`, output **int8**.
- Dense layer weights: **int8**, accumulating into **int32**.
- Scale factors chosen so the final output is in centipawns.

Training is in float; quantisation is an export step. That introduces a controlled discrepancy between
the trained network and the executed one — which is precisely what the test in §7.4(b) measures.

**Vector API** (`jdk.incubator.vector`) for the accumulator and dense-layer products, with a scalar
fallback path. Two reasons: it is where the performance is, and it is a piece of modern Java almost no
portfolio shows. Keep the scalar fallback *working and tested*, not merely present — it is the
correctness reference for the vectorised version.

### 7.4 Verification — three levels

**(a) The accumulator invariant.** The most important.

> At every position reached during a random game, the incrementally maintained accumulator must be
> **identical bit for bit** to a full recomputation.

A property test over thousands of random games with a fixed seed, comparing at every node. A bug here
crashes nothing — it just makes the engine play worse, silently and inexplicably. Without this test you
would hunt it for weeks.

**(b) Java against PyTorch.** Over a fixed position set, quantised Java inference and float PyTorch
inference must agree within the quantisation tolerance. This catches export errors: transposed
weights, wrong scales, layers in the wrong order.

**(c) SPRT, HCE against NNUE.** The final number, the one that goes in the README.

### 7.5 Training

**In PyTorch, not in Java.** The pragmatic choice, and not a surrender: training is an iterative
process that lives on its ecosystem — dataloaders, optimisers, tensorboard — and rewriting that would
be a second project disguised as the first. Inference in Java is the part that counts and the part
that is hard.

**Data.** Positions labelled with the score from a shallow-to-medium search by the engine itself in
self-play, plus the final result of the game. The loss interpolates between the two: the evaluation
teaches tactics, the result teaches what actually matters.

Start from a public dataset for the first network. Generate your own only afterwards, once the
pipeline works end to end — otherwise you are debugging training and data generation together and will
not know which is broken.

`ludus-tools` hosts the self-play generator; the training script lives in `training/` as a separate
Python project with its own `requirements.txt`. Do not try to make them share a build.

#### The generation pipeline

Built and running. Generators take jobs off a queue, play games, and publish batches of labelled
positions; a collector writes the dataset as the batches arrive. Generation and training can
therefore run at the same time on different machines, which for a project with a fixed amount of CPU
is the practical arrangement rather than a showpiece — and it is the shape the whole thing was
designed around.

The search runs **in-process** here rather than through UCI. Training needs millions of positions and
a subprocess round trip per move would dominate the cost; running the search directly also hands over
its score, which is half of what a sample is.

**Most positions are thrown away, and the filtering matters more than the volume.** A network trained
on everything learns the noise as well as the signal:

| Discarded | Why |
|---|---|
| In check | A static evaluation of a position under check describes something the network is not being asked to judge |
| Best move is a capture | The position is mid-exchange, so its static score is the illusion quiescence exists to dispel. Training on it teaches the network to believe it |
| Mate scores | Mate is distance, not evaluation, and a label of thirty thousand centipawns drags every weight it touches |
| The opening plies | They come from random moves, so they sample the book rather than chess |

**Both labels are from the side to move**, matching what the evaluation returns and what the network
is asked to predict. Mixing the two conventions is a mistake that trains perfectly well and produces
a network convinced one colour is winning.

The result is stored as an integer, not a fraction. Writing a draw as `0.5` through a default
formatter on this machine's Italian locale produces `0,5`, and a training file full of commas is a bug
discovered days later, in Python.

**Three checks on the first real run**, 4,013 samples from 4 batches:

- Mean score **−1.3 centipawns**. With symmetric self-play and side-to-move scores it has to sit at
  zero; anything else is a sign or perspective error.
- Results **1389 / 1189 / 1435** across loss, draw and win — balanced, as the same engine on both
  sides requires.
- Consecutive positions from one game read `w, b, w` with scores `75, −65, 70` and results `2, 0, 2`.
  Sign and result flip together with the turn, which is the perspective working.

**One deliberate difference from the match pipeline.** A generator publishes every batch before
acknowledging its job, so a crash in between replays the job and can duplicate samples. Duplicates are
harmless in training data and losing hours of generation is not, so at-least-once is the right trade
here — where for a match tally, counting a game twice would corrupt the verdict.

---

## 8. Testing strategy

| Test | What it protects | Where |
|---|---|---|
| Perft suite | Move generation | `core`, fast and slow (tagged) |
| **Make/unmake invariant** | State corruption | `core`, property-based |
| Null move invariant | State corruption | `core`, property-based |
| Incremental hash == recomputed | Table collisions | `core`, property-based |
| Special cases (§4.3) | Castling, en passant, promotions | `core`, unit |
| Magic tables vs ray walking | Silent attack-set corruption | `core`, unit |
| SEE | Ordering and pruning | `core`, unit |
| Colour symmetry | A sign or a table mirrored wrongly | `eval`, parameterised |
| Mate distance scoring | An engine that shuffles in a won position | `search`, unit |
| Board untouched after a search | State corruption across a whole game | `search`, unit |
| Self-play, every move checked legal | M1's exit criterion, automated | `search`, unit and slow |
| UCI protocol | GUI compatibility | `uci`, golden I/O |
| SPRT and Elo statistics | A gate that would pass anything | `tools`, unit |
| **Accumulator invariant** | NNUE correctness | `nnue`, property-based |
| Java vs PyTorch | Export correctness | `nnue`, fixed data |

Property tests use a seeded `java.util.Random` rather than a property-testing library: the generation
needed here is a random legal game, which is ten lines, and a fixed seed makes any failure
reproducible.

### 8.2 The make/unmake invariant

It deserves a separate mention because it is what makes the choice in §3.3 safe:

> After `makeMove(m)` followed by `unmakeMove(m)`, **every** field of `Board` must be identical to
> before: all bitboards, the hash, castling rights, the en passant square, the halfmove clock.

A property test over random games. An `unmakeMove` that forgets to restore castling rights produces
bugs that surface twenty nodes later, in a different subtree, as an inexplicable illegal move. This
test catches it at the node where it happens.

### 8.3 CI

Two workflows exist today:

- **CI**: build and the fast test suite on every push and pull request, plus a nightly job for the
  deep perft run. The gating job stays well under two minutes.
- **Status page**: rebuilds, recomputes the perft suite, regenerates the status page and the profile
  cards, and deploys to GitHub Pages. On every push to main and nightly.

A **release** workflow — shaded jar and launch scripts published as a GitHub Release so anyone can
play it — is still planned.

The green badge on the README makes a difference out of all proportion to the cost of adding it.

---

## 9. Milestones

Each has a verifiable exit criterion, not "when it feels ready".

| # | Content | Done when |
|---|---|---|
| **M0** ✅ | Board, bitboards, magics, pseudo-legal movegen, FEN, perft | **The full perft suite passes.** Nothing else counts until it does |
| **M1** ✅ | Negamax, minimal HCE, UCI, iterative deepening, time management, capture ordering | Plays a complete legal game against a GUI without ever proposing an illegal move |
| **M2** ✅ | Quiescence, transposition table, killers and history, SEE | Beats M1 by SPRT. This is the **Elo baseline** |
| **M3** ✅ | PVS, null move, LMR | Perft still correct, and every patch SPRT-positive on its own |
| **M4** | NNUE inference, first trained network | Accumulator invariant green, Java ≈ PyTorch, **SPRT-positive against M3** |
| **M5** | Vector API, tuning, `halfKP`, direct legal movegen | Higher nps at equal Elo, then higher Elo |
| **M6** ✅ | Status page on GitHub Pages and an SVG card for the profile | The page updates itself on every push and nightly, with no manual step |

**M0 and M1 are a weekend each.** M2 is where the engine starts being strong. M4 is where the number
for the README arrives, and from there M5 can run as long as it stays fun.

**M0 is closed.** All 32 perft cases pass, both board invariants (make/unmake reversible, incremental
hash against recomputed) are green over seeded random games, and the magic tables are validated
against ray walking. 60 tests in 3 seconds for the fast gate, 32 deep perft cases in 14 seconds
nightly.

**M1 is closed.** The engine speaks UCI, launches as a single jar from a GUI, and plays legal games.
95 tests green.

Two things migrated from M2 into M1, and the reason is worth stating. **Iterative deepening**, because
UCI time management requires it: without it there is no way to honour `go wtime` — you stop at a fixed
depth and either overrun the clock or waste it. And **capture ordering**, because alpha-beta without
ordering prunes so little that comparing M2 against an unordered M1 would measure the ordering instead
of everything else.

**Quiescence stayed out deliberately**, and not out of laziness: M2's exit criterion is beating M1 by
SPRT, and that number is only a real measurement if M1 exists without it first. The cost was visible
by eye in the UCI output — the score swung roughly ±100 centipawns between odd and even depths,
because at odd depths the engine gets the last capture and at even depths its opponent does. The
horizon effect in pure form, and the first thing M2 fixed.

The important property of this schedule: **M1 is already a publishable repository**. An engine that
plays legal games over UCI with a green CI badge is a finished project, not a building site.
Everything after it is incremental improvement on a base that stands on its own. That is the insurance
against the half-abandoned repository.

**M2 is closed, and it is the first milestone with a real number.**

| | |
|---|---|
| Result | **186 wins, 12 draws, 2 losses** over 200 games — 96.0% |
| Elo | **+552 ± 106** against M1 |
| SPRT | bound crossed at **game 11** (11-0-0), final LLR +55.7 against bounds of ±2.94 |
| Conditions | 100 openings played with colours swapped, 100 ms per move, 64 MB hash |
| Illegal moves | **zero** |

The interval is wide because Elo is inherently imprecise at extreme scores: at 96% each additional
game barely moves the estimate. The number to read is not "552" but "the lower bound is +446", which
is enormous anyway.

What produced it, in plausible order of weight: **quiescence** (most of it), then the **transposition
table** — which besides avoiding re-searched transpositions supplies the previous iteration's best
move, the most valuable ordering hint available — then **killers, history and SEE** on the ordering. I
did not separate the contributions with individual SPRTs. That would have been the correct approach
and I did not do it, so the breakdown above is reasoned guesswork rather than a measurement. From M3
onwards the rule "one patch at a time with its own SPRT" gets applied for real, and §9.0 is what
happened when it was.

The effect of quiescence is visible without a match at all, in the UCI output on one position:

```
M1:  depth 1  cp  25    depth 2  cp -140    depth 3  cp  50
M2:  depth 1  cp  25    depth 2  cp   -5    depth 3  cp  15
```

Swings of ±165 centipawns became ±30. Exactly the horizon effect §5.2 predicted, measured before and
after.

### 9.0 M3 — where "one patch at a time" paid for itself

M3 is the first milestone where every patch got its own SPRT against the previous version. At the end
of M2 I had written that the breakdown of contributions was "reasoned guesswork rather than a
measurement", and that from here the rule had to be applied for real. The first experiment explained
why.

#### Patch 1 — null move pruning: **−16 ± 36 Elo, rejected**

300 games, 120-46-134, LLR −0.62. Inconclusive and trending negative.

The number is not the finding, though. The patch was **provably inert**, and the reason is instructive.

Its guard was `isPv = beta - alpha > 1`, meaning *do not attempt a null move at a full-window node* —
correct in principle: speculation belongs where being wrong costs a re-search rather than corrupting
the score the engine acts on.

But in M2's code **there was not a single null-window search anywhere**. Every child was called with
`(-beta, -alpha)` inherited from its parent, and the only narrow-window call in the whole file was the
null move probe itself, which recurses with `allowNull=false`. So the condition was true at every node
and the block never ran. The −16 is noise plus the cost of evaluating the guards.

**Without a per-patch SPRT I would have shipped a feature that does nothing**, and listed it in the
README. It is exactly the failure the gate exists to catch, and it happened on the first attempt.

#### Patch 2 — PVS: **+119 ± 43 Elo, accepted**

242 games, 143-36-63, 66.5%, LLR +2.97, bound crossed.

Principal variation search gives every move after the first a one-point scout window — "does this beat
alpha, yes or no?" — and searches properly only the ones that answer yes. It is worth having on its
own, but above all it **creates the null-window nodes that did not exist before**, so patch 1's null
move fires for the first time.

Honesty about attribution: this number **does not separate PVS from null move**, and cannot, because
one is inert without the other. That is not sloppiness in the experiment — it is a property of the
code, and a measured one: patch 1 alone was worth nothing, and that is the proof. The two are a single
change, "make reduced-window searching work", and were measured as such.

Independent confirmation, same position at depth 4: **3641 nodes against 2952**, down 19% at equal
depth.

#### Patch 3 — late move reductions: **+58.7 ± 27 Elo, accepted**

544 games, 272-91-181, 58.4%, LLR +2.97, bound crossed.

If the ordering is any good, a quiet move sitting eighth in the list is not the best move, and
searching it to full depth is work spent confirming something already likely. Search it shallower; if
the shallow search is wrong and the move beats alpha anyway, the mistake is caught and paid for
immediately with a re-search.

Exempt: captures, promotions, checks given or received, and the first few moves in the list. Those are
the cases where a missed line means material or the game, not an imprecision.

**A methodological note.** The first match ended at 300 games with **+56.1 ± 35, LLR +1.68** —
inconclusive, book exhausted. The 95% interval was already entirely above zero, and calling it good
there would have been easy. But the declared rule is that the SPRT decides, not the eye, so I extended
the book and replayed: 544 games, bound crossed, **+58.7**. The point estimate barely moved — the
larger sample did not "find" a result, it reached the standard of proof set before any numbers were
seen.

#### Milestone result

**M3 against M2: +181.7 ± 39.5 Elo**, 195-54-51 over 300 games, 74.0%, bound crossed at game 85.

A useful consistency check: the two accepted patches measured in a chain gave +119 and +58.7, summing
to +177.7. The direct measurement lands within four Elo of that. Chained comparisons are holding
together.

#### A known limitation of the match runner

An inconclusive result costs **replaying everything from scratch**: the runner cannot resume or extend
an existing match. With games measured in minutes that is real waste, and it is the first thing to fix
when M4 starts producing patches worth a few Elo, where inconclusive results will be the norm rather
than the exception.

#### What stayed out of M3, and why

**Futility pruning** and **direct legal move generation** did not land. Not out of time pressure
dressed up as a decision: direct legal generation is a high-risk rewrite of the most delicate code in
the engine, whose payoff is nodes per second rather than Elo, and it belongs beside the Vector API
work in M5 where performance is the theme. Futility is the natural candidate for the next single
patch.

### 9.05 M4 — three gates of four

**M4 is not closed.** Its exit criterion has four parts, and the fourth fails.

| Gate | Result |
|---|---|
| Accumulator invariant | ✅ held bit for bit over **73,862 positions** |
| Java matches PyTorch | ✅ **worst gap 4 centipawns** across ten fixtures |
| The seam holds | ✅ the network loads through a UCI option; `ludus-search` unchanged and unable to name it |
| **SPRT against M3** | ❌ **−589 ± 147 Elo**, 4-5-191 over 200 games, H0 accepted after 12 |

The network is worse than the evaluation it was meant to replace, by a lot. It does not become the
default, because the rule was stated before the number was known.

#### The distinction the agreement test buys

Without it, a result like this leaves two possibilities: the network is weak, or the engine is
running it wrongly. They call for completely different work, and guessing which one costs days.

The engine reproduces PyTorch's own answer to within 4 centipawns on every fixture, and the
incremental accumulator matches a full recomputation exactly. So the implementation is faithful, and
**the network itself is the problem**. That is worth more than the Elo figure.

#### It is not mainly weak. It is slow.

The obvious explanation was too little training data, and it was stated before the match was run. It
is also, as it turns out, not the main problem — which is what §1.3 exists to establish:

```
hand-crafted         depth 8   2,160,818 nodes    346 ms   6,245,138 nodes/s
network ludus.nnue   depth 8   3,097,290 nodes   9421 ms     328,764 nodes/s
```

**Nineteen times slower.** At a fixed allowance per move that is two to three plies of depth given
up, and two to three plies at this strength is worth several hundred Elo on its own. The −589 is
mostly a search that never got going, not an evaluation that misjudges.

This is precisely the distinction §1.3 was written for: *searching worse* shows up as Elo falling
with the node rate unchanged, and *searching slower* shows up here. Without the benchmark, the
obvious story — "it needs more data" — would have been believed, and weeks of generating positions
would have bought nothing.

The cost is the dense layers. The first one is 512 inputs into 32 neurons: sixteen thousand
multiply-accumulates at every leaf, against a couple of hundred operations for the hand-crafted
evaluation. The accumulator is already incremental and is not the bottleneck.

#### Making it faster, and what the measurements actually said

| | nodes/second | |
|---|---:|---|
| hand-crafted evaluation | 6,299,760 | the target |
| network, first version | 328,764 | 19× slower |
| after widening the weights to `int` | 793,566 | **2.4× from a ten-line change** |
| with the Vector API | 866,617 | +9% on top |

**The ten-line change was worth more than the SIMD.** The file stores dense weights as bytes because
that is what they are worth, and inference multiplied them against `int` activations — a sign
extension on every one of seventeen thousand products, in a loop shape the JIT will not vectorise.
Widening them once at load costs 68 kilobytes and removed both problems.

The explicit Vector API then added only nine percent, and the reason is worth recording: **C2 had
already vectorised the widened loop by itself.** Integer addition can be reordered freely, so an
integer reduction is something the JIT is allowed to vectorise and does. The common assumption that
hand-vectorising is required to get SIMD out of the JVM is, at least here, wrong — and it took a
measurement to find out rather than an afternoon of guessing.

The Vector API code stays anyway. It is nine percent for free at run time, it is verified to agree
with the scalar loop exactly on every length from zero to six hundred, and it is the foundation for
the change that would actually matter: **int8 lanes**. A 256-bit register holds thirty-two bytes
against eight ints, so working in the width the weights are actually stored in is worth roughly
another fourfold — which is how the real implementations reach millions of nodes per second.

`SPECIES_PREFERRED` asks the JVM for the widest registers the CPU has rather than hardcoding a width:
eight lanes here on AVX2, sixteen on AVX-512, four without either, same source. That portability is
the argument for the Vector API over hand-unrolling, more than the raw speed is.

**The scalar path is kept working and tested, not merely present.** It is the correctness reference,
and it is what actually runs in a GUI: `jdk.incubator.vector` has to be asked for with
`--add-modules`, and every GUI launches an engine as a bare `java -jar`. So the fast class is loaded
reflectively and its absence is a configuration fact rather than an error.

#### Speed was real, and not the answer

With inference 2.6 times faster, the match was re-run: **−541 ± 133**, against −589 before. Fifty Elo
for a 2.6× speedup. Real, and nowhere near enough — which retires the hypothesis rather than
confirming it, and is why it was re-measured instead of assumed.

So the network was interrogated directly rather than theorised about further. It is not broken:

```
starting position          +29 cp        white a queen up      +976 cp
white a queen down         -966 cp       white a rook up       +573 cp
king and queen v king      +869 cp       the mirror            -828 cp
```

It understands material perfectly well. And the data was not the obvious problem either — 35% of the
training positions are beyond ±300 centipawns, so it saw plenty of imbalance.

#### What it actually is

Comparing the two evaluations on the same positions, which is what `bench --compare` exists for:

| position | hand-crafted | network | difference |
|---|---:|---:|---:|
| opening | 10 | 30 | +20 |
| Kiwipete | 115 | 150 | +35 |
| quiet middlegame | 10 | 6 | −4 |
| tactical middlegame | 135 | 108 | −27 |
| **rook endgame** | **−31** | **+269** | **+300** |
| **pawn endgame** | **65** | **158** | **+93** |
| **rook endgame** | **−356** | **−539** | **−183** |

Mean absolute difference: 88 centipawns.

**In the middlegame the network reproduces the hand-crafted evaluation to within a few tens of
centipawns. In endgames it is wrong by up to three hundred.** Both halves of that matter:

- Where it agrees, it has **no upside**. Its labels came from searches by the very evaluation it is
  imitating, so at best it reproduces its teacher — while costing seven times as much to compute. A
  faithful copy at seven times the price is strictly worse, and no amount of extra data from the same
  teacher changes that.
- Where it disagrees, it is **wrong**. Self-play games between equal engines mostly end in the
  middlegame or by the fifty-move rule, so endgames are thin in the data — and endgames are exactly
  where the tapered evaluation behaves differently, with the king becoming a fighting piece.

That is the whole −541, in three measured parts: two plies given up to speed, no gain where it copies
correctly, and real losses where it copies badly.

#### What would actually fix it

Not more data from the same teacher. **A better teacher** — labels from much deeper searches, so the
network learns something the hand-crafted evaluation does not already encode — and **endgame
coverage**, which self-play between equals will not produce on its own and which usually comes from
seeding games from endgame positions rather than only from the opening.

#### What this reorders

**M5's Vector API work is a prerequisite for M4's exit criterion, not a follow-on.** A network that
cannot be evaluated quickly cannot win a match however well it is trained, so the order in the
milestone table is wrong and the work should follow the measurement instead.

The data problem is real and still needs solving — 252,000 positions where working networks use tens
of millions, labels from depth-5 searches of a roughly 2000 Elo engine, and validation loss still
falling at the last epoch. But it is now second in line. The generation pipeline manages about 30,000
positions a minute on one machine, so twenty-five million is an evening across two, which is what the
queue was built for.

> **Both claims in that paragraph turned out to be wrong, and are corrected below.** The throughput
> figure was measured on a mixed run and does not describe either kind of position (§9.06); the ordering
> — data second in line — was reversed by §9.07, which found the network losing to the hand-crafted
> evaluation as a *predictor*, and by the observation about validation loss in this very paragraph, which
> was written down and then not acted on.

The infrastructure for all of it is built and verified. The order of the remaining work is what
changed.

### 9.06 Acting on the diagnosis

Both halves of "a better teacher and endgame coverage" turned into code.

**Endgames are constructed, not played into.** `EndgameSeeds` places a king plus a plausible material
set — K+R v K+R, K+Q v K+R, rook-and-pawn, bare pawn endings, and eleven others — on random squares
and keeps the position only if the kings are not adjacent, no pawn sits on the first or eighth rank,
the side that just moved is not still in check, and the side to move has a legal reply. Walking
further into self-play games would not have worked, because the games do not go there: they end in
the middlegame or by the fifty-move rule. `--endgame-fraction` (default 0.35) decides the share, and
the jobs are interleaved rather than grouped so a run cut short still contains both kinds.

**Depth was chosen by measuring rather than guessing** — and then the measurement had to be redone,
because the first one was taken on a mixed dataset and the two kinds of position do not cost remotely
the same. Twenty-two threads on this machine, measured separately:

| | Samples/minute |
|---|---:|
| Openings, depth 6 (the original teacher) | 76,800 |
| Openings, depth 8 | 21,700 |
| **Openings, depth 10** | **5,850** |
| **Endgames, depth 10** | **58,600** |

An endgame position searches about **ten times faster** than a middlegame one at the same depth, which
is obvious in hindsight — six pieces is a far smaller tree than thirty-two — and which invalidated both
the throughput estimate and, more seriously, the scheduler. Openings dominate the cost, so a
700,000-position dataset at depth 10 is about ninety minutes rather than the two hours the mixed figure
suggested. Since the diagnosis said the labels were *poor*, not *few*, depth buys more than volume, and
depth 10 is affordable.

#### The scheduling bug this exposed

The first implementation split the generating **threads** by the target fraction: with 22 threads and
`--endgame-fraction 0.35`, eight threads made endgames and fourteen made openings. Thirty-six per cent
of the threads, which is essentially the thirty-five asked for, and that near-match is exactly why it
looked right.

It produced a dataset that was **91% endgames**, measured over 642,605 positions — the mirror image of
the problem it was built to fix, with middlegames down to about 5%. Eight threads on the cheap kind
outproduced fourteen on the expensive kind by roughly ten to one. Nothing failed; the run reported
success.

The fix is to decide from **what has been written** rather than from which thread is asking, which
self-corrects whatever the speed ratio turns out to be — including on hardware where it is different.
Re-measured after the fix: 26% seeded from endgames, and **43% of positions with eight pieces or
fewer**, because opening games also reach endgames by being played. Those two numbers are not the same
quantity, and conflating them is what made 38% look acceptable in the first smoke test.

`DatasetBalanceTest` asserts the composition under deliberately lopsided yields, and runs the old
policy through the same simulation so the property is demonstrably not free.

**The distributed path has the same defect, less severely.** `collect` publishes a fixed proportion of
endgame jobs, so it balances *games*, not *samples* — and an endgame game yields a different number of
positions than an opening one. It is bounded by the samples-per-game ratio rather than by the ten-to-one
speed ratio, so the skew is real but nothing like 91%. The honest fix is for the collector to classify
the batches it already receives, by piece count, and top up whichever kind is behind: the queue then
becomes a control loop rather than a fixed work list, which is a better argument for it being there than
"work can be spread across machines" was.

**The queue stopped being mandatory.** `collect --local` generates in-process across N threads with
no broker at all. RabbitMQ earns its place when generation is spread across machines or run alongside
training; requiring it to produce a dataset on one laptop was friction I had introduced, and it kept
CI out of the pipeline entirely. The distributed path is unchanged and still the one the architecture
diagram describes — there is now simply a way to do the same work without it.

### 9.07 The measurement that revised the diagnosis

§9.05 concluded, from `bench --compare`, that the network reproduced the hand-crafted evaluation in the
middlegame and diverged by up to 300 centipawns in endgames. That was measured correctly and read too
narrowly, because **agreement with the hand-crafted evaluation is the wrong axis** once the labels come
from deep searches. A network trained on ten-ply verdicts *should* disagree with the raw evaluation:
that disagreement is the knowledge it is supposed to hold. Scoring it on agreement rewards the defect.

`bench --predict` asks the question the right way round. Given a position and what a deep search
concluded about it, which evaluation is closer? The hand-crafted one is a fair baseline precisely
because the labels were produced by searching with it, so a network that predicts those verdicts better
has absorbed something the evaluation does not contain — which is the entire premise of NNUE.

The first network, against depth-10 labels on 8,211 sampled positions:

| Phase | Positions | Hand-crafted | Network |
|---|---:|---:|---:|
| Bare endgame | 3,543 | 90 | 136 |
| Endgame | 877 | 148 | 178 |
| Late middlegame | 998 | 95 | 157 |
| Middlegame | 1,363 | 57 | 147 |
| Opening | 1,430 | 31 | 127 |
| **All** | **8,211** | **81** | **143** |

Mean absolute error in centipawns. **It is worse than the hand-crafted evaluation in every phase**, 143
against 81. That is a far sharper statement than "a lossy copy with no upside", and it explains −541 Elo
without appealing to speed at all.

#### Why a mean in centipawns is not enough

That table nearly repeated the mistake of §9.05 in subtler form. Training minimises squared error on
`sigmoid(cp/400)`, which at 2000 centipawns reads 0.993 — **saturated**. So the network is barely taught
to separate +500 from +900, and correctly so: both are winning and the move played is the same. A mean
in centipawns punishes precisely that irrelevant distinction, so a network could score badly for a
reason that costs no Elo at all.

Split by label magnitude, and reported in win-probability terms as well — the space the loss actually
minimises — the two cases separate:

| \|label\| | Positions | Hand cp | Net cp | Hand win% | Net win% |
|---|---:|---:|---:|---:|---:|
| 0–50 | 2,089 | 42 | 117 | 0.026 | 0.071 |
| 50–150 | 1,608 | 34 | 132 | 0.021 | 0.079 |
| 150–400 | 2,305 | 45 | 131 | 0.026 | 0.070 |
| 400–1000 | 1,526 | 113 | 160 | 0.046 | 0.059 |
| 1000–2000 | 480 | 487 | **364** | 0.089 | **0.052** |

The concern was legitimate and the answer is decisive: **2.7 to 3.8 times worse in win-probability terms
in every band near level**, which is where the sign of a difference decides which move gets played. The
defect is real, not an artefact of the metric.

And it wins exactly one band — 1000–2000, where the hand-crafted evaluation is off by 487 centipawns and
the network by 364. It learned to recognise thoroughly winning positions, which no engine needs help
with, and failed at everything that decides a move.

The verdict therefore reads the near-level bands, not the overall mean. **A metric that can be passed by
being right where it does not matter is not a gate.**

**It also corrects where §9.05 aimed.** Endgames do carry the largest absolute error, but the worst
*proportional* gap is in the opening — 31 against 127, a factor of four and a half. The network is not
weak in endgames; it is weak everywhere, and the endgame number was simply the part that showed up when
it was compared against its own teacher. Endgame coverage remains worth having, and is no longer the
headline.

The finding fits something recorded in §9.05 and not acted on: **validation loss was still falling at
the last epoch.** A network that cannot beat the raw evaluation as a predictor, and was still improving
when training stopped, is *undertrained* as well as badly taught. So the checkpoint-and-schedule changes
may matter as much as the deeper teacher — which makes them two hypotheses under test at once, against
the one-at-a-time rule of §5.5. Accepted here deliberately: that rule governs patches judged by Elo,
where attribution is the whole difficulty, and this test is cheap enough to re-run with either half
removed if the result needs attributing.

**This check now runs before the SPRT**, in `tools/retrain.ps1`. It costs a minute. The SPRT costs hours
and, for the first network, would only have confirmed what the minute already said.

### 9.1 M6 — the status page

Last by design: it collects the numbers the earlier milestones produce, so those have to exist first.

The idea is a page that explains on its own how the engine is doing — Elo per milestone, nps, the
perft verdict, the history of patches and their SPRTs — generated by the workflows and updated with no
manual step.

**The constraint that determines its shape:** GitHub READMEs **sanitise HTML**. No `<iframe>`, no
`<script>`, no CSS. So "attaching an HTML page to the profile" is not literally possible, and what
works comes in two pieces:

1. **The full page on GitHub Pages**, served from the `ludus` repository. Here HTML is unrestricted.
2. **An SVG card for the profile**, generated by the same workflow and linked to the page. SVG passes
   sanitisation because it is an image — the same mechanism the profile already uses for its language
   chart.

On the theme: the board. The 8×8 grid is already a layout system and the square colours give the
palette, so the theme is not decoration applied on top — it is the structure of the data. Three things
on the page come from the subject rather than being ornament: the Elo result drawn as the evaluation
bar an analysis board shows, the milestones as a numbered ladder because they genuinely are a sequence,
and each perft position drawn as a real board from its own FEN — the position the numbers beside it
were counted from.

One precaution worth fixing now: the card is generated in **two variants**, light and dark, referenced
with `<picture>` and `prefers-color-scheme`. A card that only reads on a white background is half
broken.

**A note on what the generator refuses to do.** If a recomputed perft count disagrees with the
published one, it fails instead of publishing. A page that said "verified" when it was not would be
worse than no page.

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| Silent move generation bugs | Perft first, always. Never write search on unvalidated movegen |
| Direct legal movegen breaks correctness | Stage one stays in the repository as the oracle |
| Losing weeks tuning HCE | An explicit timebox. HCE is scrap material (§6.1) |
| Poor training data | First network from a public dataset, self-play only afterwards |
| GC pauses in the search | Zero-allocation discipline, verified with JFR (§3.3) |
| Act II slipping indefinitely | M1 is already shippable. The repository does not die either way |
| Elo not rising and nobody knowing why | nps and Elo measured separately (§1.3), one patch at a time |

---

## 11. What to say in the README

Code does not speak for itself. The three things that make this project interesting to read about, and
worth writing properly:

1. **The number.** "The NNUE added N Elo, measured by SPRT over M games, here is the match
   configuration." With the chart. It is the rarest thing in a machine learning portfolio.
2. **Java as an interesting constraint.** Zero allocation in a hot loop, the parallel-array table
   layout, the absence of `PEXT`, the Vector API, verifying inlining. Nobody writes about this: every
   serious engine is in C++ or Rust. Original content, not a summary of the wiki.
3. **The seam.** Why the evaluation boundary sits exactly where it does, and what putting it elsewhere
   would have cost. This is the design work a competent reader recognises, and it is worth more than
   ten patterns listed by name.
