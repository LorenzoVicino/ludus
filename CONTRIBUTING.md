# Contributing

The rules here exist because a chess engine is unusually good at hiding regressions. A change can be
elegant, pass every test, look obviously right — and lose games. So the bar is not "does it work", it is
**"what is the number".**

## The two things that cannot break

**1. Move generation stays exact.** `perft` counts the leaves of the legal move tree, and the counts for
standard positions are published. There are 197,281 legal games four moves long from the start position —
not about that, exactly that. Any change that touches `ludus-core` must leave all 32 cases in
`perft-suite.txt` correct:

```bash
./mvnw test -Pslow
```

A single node out is a bug, and `perft divide` narrows it to one root move.

**2. Strength is measured, not argued.** No change to search or evaluation lands on the strength of how
sensible it reads. The new version plays several hundred games against the old one and a sequential test
decides whether the difference is real:

```bash
./mvnw -DskipTests package
cp ludus-uci/target/ludus.jar build/baseline.jar     # before your change
# ...make the change, rebuild...
cp ludus-uci/target/ludus.jar build/candidate.jar

java -jar ludus-tools/target/ludus-match.jar local \
    --engine-a "java -jar build/candidate.jar" \
    --engine-b "java -jar build/baseline.jar" \
    --pairs 250 --movetime 100 --concurrency 8 --sprt 0 10
```

Exit code `0` accepted, `1` rejected, `2` no decision. **If it does not pass, the change does not land.**

## One patch at a time

Three improvements in one pull request, and a drop in strength tells you nothing about which one caused
it. Each search or evaluation change gets its own commit and its own match.

This is not theoretical here. Null move pruning was rejected at **−16 ± 36 Elo** because its guard
correctly refused to fire on wide search windows and the engine had none until principal variation search
created them. Shipped as part of a batch, it would have been a feature that did nothing, forever. The
account is in [`DESIGN.md`](DESIGN.md) §9.0.

## Changes to main go through a pull request

`main` is protected: pushes must arrive as a pull request, CI must be green, force pushes and deletions
are refused. Not process for its own sake — the history of this repository is meant to read as a record of
what was measured, and a direct push is a claim nobody reviewed.

```bash
git switch -c short-description-of-the-change
# work, commit
git push -u origin short-description-of-the-change
gh pr create
```

**Including for the owner.** The ruleset has no bypass actors, so a direct `git push` to `main` is
rejected for everybody. A bypass that gets used every day is not a policy, it is a comment.

Two consequences worth knowing rather than discovering. Approvals are set to **zero**, so a solo change
still merges — the requirement is the pull request and the green check, not somebody else's time. And the
only escape hatch is *administering* the rule rather than stepping around it: if CI is broken and something
has to land, the ruleset gets edited deliberately and visibly, which is the correct amount of friction for
that decision.

This is also why the status-page workflow no longer commits anything: it was the one writer that could not
open a pull request, and a personal repository cannot grant a ruleset bypass to GitHub Actions. It now
publishes to Pages instead, which it was already doing.

## What a good commit message looks like here

Say **what you measured**, not what you intended. The messages in this repository are long on purpose:
they carry the numbers and the reasoning, so the design document and the history do not disagree.

Include the failed attempts. A commit that records "this looked right and measured at nothing, here is
why" is worth more than one that records a success, because the next person will have the same idea.

## Wrong turns belong in the record

[`DESIGN.md`](DESIGN.md) keeps departures from the plan **next to the original reasoning** rather than
edited out. If your change contradicts something written there, add the correction beside it and say what
the measurement was. A design document that stops tracking reality stops being worth reading.

The same goes for a measurement you got wrong. Several entries in there exist because a number was quoted
confidently and turned out to be an artefact of how it was measured; those are the most useful entries in
the file.

## Running things

```bash
./mvnw verify                    # build and the fast suite
./mvnw test -Pslow               # deep perft, long self-play games, container-backed tests
docker compose --profile demo up -d    # the web service on localhost:8080
```

The slow suite needs Docker for the service's integration tests. The fast suite does not need anything.

## Style

Match the surrounding code. Two things are specific to this project rather than to taste:

- **The search must not allocate.** No objects in a loop that runs millions of times per second: moves
  are packed into `int`s and every buffer is preallocated per ply. If you add allocation to a hot path,
  the benchmark will show it (`bench --depth 8`) and it will cost Elo.
- **Comments explain why, not what.** The code already says what it does. What it cannot say is which
  alternative was tried, what it measured, and why this version won.
