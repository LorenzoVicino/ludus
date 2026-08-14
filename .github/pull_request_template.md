<!--
  Keep whichever sections apply and delete the rest. The point of this template is not paperwork: it is
  that the two questions below are the ones this project answers with numbers rather than with prose.
-->

## What this changes

<!-- One or two sentences. What behaviour is different afterwards? -->

## The number

<!--
  Required for any change under ludus-search or ludus-eval. Paste the match result — the whole verdict
  line, not a summary of it.

  java -jar ludus-tools/target/ludus-match.jar local \
      --engine-a "java -jar build/candidate.jar" \
      --engine-b "java -jar build/baseline.jar" \
      --pairs 250 --movetime 100 --concurrency 8 --sprt 0 10

  If the change cannot affect strength — tooling, the web service, documentation — say so and delete the
  rest of this section.
-->

```
A relative to B:
LLR:
verdict:
```

## Correctness

- [ ] `./mvnw verify` passes
- [ ] `./mvnw test -Pslow` passes, if anything under `ludus-core` was touched (all 32 perft cases exact)

## What was tried and did not work

<!--
  Optional and genuinely valued. If you tried something first that measured at nothing, say so here or in
  DESIGN.md — the next person will have the same idea, and knowing it was already measured is worth more
  than the patch that did land.
-->
