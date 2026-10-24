# Bounded eager FSM occurrence and stack scaling

This fixture-only F-058.F slice exercises ordinary eager construction. It changes no production API or limit, registers no pending boundary work, and uses no diagnostic capacity policy. It is not full Foundation58/F-058.F acceptance, target execution, waveform evidence, or a performance guarantee.

## Assertions

Three separately executed profiles each capture two fresh Sessions and compare complete ConstructionSnapshot values and their raw UTF-8 representations in memory. Only snapshot hashes, sizes, counts and elapsed observations are printed.

- single: one four-state stack leaf, capacity2, depth1, two frames, six stack bits.
- parallel: four shared-definition stack leaves under one parent, capacity8, five machine occurrences, depth2,32 frames,80 stack bits.
- nested: two branches each owning two shared-definition stack leaves, capacity32, seven machine occurrences, depth3,128 frames,280 stack bits.

Checks cover exact branch/leaf slot sets, parent and owner-case ownership, composition roots, unique machine/declaration/storage paths, a common authored clock domain, actual register declarations, pointer and frame types, uninitialized frames, and exact stack storage arithmetic. The canonical owner-case ID is compared to the parent's authored initial case, not its display name. A standalone stack requires absent composition metadata; composed profiles require it. Stack bits are pointerWidth + capacity * localWidth per leaf, with the captured four-state local width asserted to be two. This count excludes other control/local storage.

## Reproduction

Use the existing tests/scala/domain-hierarchy/single-assignment-accounting/run_single_assignment.py with python3 -I -B and --manifest pointing to scale.fixtures.json. Supply its required repository, Java, compiler/runtime classpaths, bridge jars, effective-source-map, fresh ignored output-root and positive timeout arguments. The manifest requires candidate API SHA256 4a3e63583768836d83827fb2eb4e2fe28bd2a2947696a2d59fce4a072c83a204. Supply that same JAR for the required baseline-api-jar argument: every suite selects candidate, and no A/B comparison is claimed.

Supply --scale-source-root with the exact97-source authoritative projection and the fixture copied to tests/scala/reusable-fsm/fixtures/scale/FsmScale.scala. Supply --scale-source-pins with JSON rows containing virtual_path and sha256 for the fixture and build.mill. The projection must have a regular hash-pinned build.mill and core/scala and docs directories. Fixture copies must have one link; immutable production source links are permitted. The shared runner validates pins and markers, clears JVM option environment overrides, compiles with -Wunused:all -Werror, and requires all three success markers. It performs no API/native rebuild.

Construction timing fields are observations, not pass thresholds. Snapshot hashes are not golden constants across changed source layouts. Per-process peak RSS and process elapsed observations in the original bounded exploration were obtained with os.wait4 on each Java process separately, not cumulative child rusage; the maintained runner does not claim to measure those metrics. The exploration observed maxRSS193324/220124/222512 KiB and process elapsed1.329/1.971/2.420 seconds for single/parallel/nested on this environment, including two captures in each process. These are one-run measurements, not benchmark guarantees.
