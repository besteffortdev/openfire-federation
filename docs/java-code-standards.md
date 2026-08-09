# Java code standards for this project

Two halves that answer two different questions:

- **Part 1 — what goes wrong.** The ranked list of the most-cited complaints about AI-generated
  Java (2022–2026), built first and used for the 1.10.1 refactor pass. Failure-driven.
- **Part 2 — what good looks like.** The widely-agreed Java canon — Oracle, Google, *Effective
  Java*, SEI CERT, OWASP, SonarSource — plus the professional-ethics codes that have concrete
  code-level consequences. Rule-driven.

Part 1 tells you what to look for in a review. Part 2 tells you what to write in the first place.
Every Part 2 item carries this repo's status: **PASS** (checked, don't re-fix), **GAP** (real,
actionable), **JUSTIFIED** (deviates knowingly, reason recorded), or **N/A**.

---

## Part 1 — The AI-code complaint ranking

Ordered by evidence weight, not by how annoying they feel.

| # | Complaint | Evidence |
|---|---|---|
| 1 | **"Almost right, but not quite"** — plausible logic with a subtly wrong branch | 66% of developers, Stack Overflow Developer Survey 2025 |
| 2 | **Missing or swallowed error handling** | ~2× the human rate (CodeRabbit) |
| 3 | **Security defects** | Veracode: insecure in 45% of tasks; **Java worst at ~72%** |
| 4 | **Duplication instead of reusing an existing helper** | GitClear: duplicated blocks up 8× in 2024 |
| 5 | **God methods / god classes** | Cognitive-complexity violations dominate Sonar scans |
| 6 | Null handling and NPE risk | |
| 7 | Comment noise — restating *what*, never *why* | |
| 8 | Hallucinated APIs and methods | |
| 9 | Missing input validation at trust boundaries | |
| 10 | Style drift within one codebase | |
| 11 | Over-engineering / speculative generality | |
| 12 | Tautological tests that assert the implementation | |
| 13 | Concurrency misuse | |
| 14 | Performance anti-patterns | |
| 15 | Magic numbers | |

---

## Part 2 — The agreed canon

### Sources and what each is actually good for

| Source | Weight | Use it for |
|---|---|---|
| [Oracle Code Conventions for Java](https://www.oracle.com/java/technologies/javase/codeconventions-namingconventions.html) | Historic baseline (1997, never updated) | Naming and file layout only. Still the reference everyone means by "standard Java naming". |
| [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) | Enforceable, mechanical | Formatting, and a short list of real rules (no wildcard imports, always braces, never ignore a caught exception silently). Deliberately avoids subjective advice. |
| *Effective Java*, 3rd ed. (Bloch) | The design canon | 90 items / 11 chapters. API design, object lifecycle, exceptions, concurrency. Cited by the JDK's own authors. |
| [SEI CERT Oracle Coding Standard for Java](https://cmu-sei.github.io/secure-coding-standards/sei-cert-oracle-coding-standard-for-java/) | The security/robustness canon | ~180 normative rules across 20 categories (IDS, ERR, OBJ, MET, VNA, LCK, THI, FIO, SER, SEC…). Rules are requirements; recommendations are guidance. |
| [OWASP Secure Coding Practices](https://owasp.org/www-project-secure-coding-practices-quick-reference-guide/) | Checklist | Language-agnostic per-area checklist: input validation, output encoding, error handling and logging, data protection, file management. |
| [SonarSource Java ruleset](https://www.sonarsource.com/blog/top-issues-in-java-projects/) | Empirical | 600+ rules, and — unusually — published data on which violations actually correlate with faults. |
| [ACM Code of Ethics](https://www.acm.org/code-of-ethics) + [ACM/IEEE-CS Software Engineering Code](https://www.computer.org/education/code-of-ethics) | Professional obligation | The eight principles (PUBLIC, CLIENT, PRODUCT, JUDGMENT, MANAGEMENT, PROFESSION, COLLEAGUES, SELF) and their code-level consequences. |

A note on the *principles* (DRY, KISS, YAGNI, SOLID): these are **tensions to manage, not rules to
pass**. DRY and KISS pull against each other — removing a duplication sometimes costs more
complexity than the duplication did. Treat a "violation" of one as a prompt to justify the
trade-off, never as a defect on its own.

---

### A. Exceptions and failure
*CERT ERR-\*, Effective Java ch. 10, Sonar S112/S1166*

| Rule | Status here |
|---|---|
| **ERR00-J** Never suppress or ignore a checked exception | **JUSTIFIED** — 12 `catch (… ignored)` blocks. All are genuine best-effort cleanup (`Files.deleteIfExists`, `socket.close`) or a parse whose failure has a defined fallback. Google's rule allows this *if the exception variable is named `ignored`/`expected`* — which it is. Keep that naming; it is what makes the intent reviewable. |
| **ERR07-J** Do not throw `RuntimeException`, `Exception`, or `Throwable` (Sonar S112) | **PASS** — one `throws Exception` left, `FederationIQHandler.parsePacket`. |
| **ERR07-J (catch side)** Do not catch `Exception`/`Throwable` where a narrower type exists | **GAP** — 97 broad catches: 37 in `FederationManager`, 20 in `FederationIQHandler`, 18 in `FileRelayManager`. Some are deliberate isolation barriers around Openfire internals; most are not. |
| **ERR01-J / IDS15-J** An exception must not expose sensitive info across a trust boundary | Review item — federation error stanzas travel to peers. |
| **ERR03-J** Restore prior object state on failure (EJ Item 76, failure atomicity) | **PASS** in the relay: a failed transfer deletes the part file, tombstones, and fails parked requests as one unit. |
| **ERR04-J / ERR05-J** Never complete abruptly from `finally` | **PASS** |
| **EJ Item 69** Use exceptions only for exceptional conditions | **PASS** |
| **EJ Item 75** Include failure-capture information in the detail message | Mostly pass — log messages carry the id/peer/reason. |

### B. Security and trust boundaries
*CERT IDS/FIO/SEC/MSC, OWASP*

| Rule | Status here |
|---|---|
| **IDS03-J** Do not log unsanitized user input (log forging) | **GAP** — `FileRelayManager:985` and `FederationIQHandler:1961` log a peer-supplied `reason` attribute verbatim. XML attribute-value normalization folds a literal newline to a space, but a `&#10;` character reference survives it, so a hostile peer can forge log lines. |
| **IDS17-J** Prevent XML external entity attacks | **GAP** — `FederationFileConfig:82` uses a bare `new SAXReader()`. The input is admin-owned `conf/openfire.xml`, so the practical risk is low, but the rule is normative and the fix is three `setFeature` calls. |
| **IDS16-J** Prevent XML injection | **PASS** — stanzas are built through dom4j `Element`/`addAttribute`, never string-concatenated. |
| **FIO16-J** Canonicalize path names before validating them | **PASS** — every remote-supplied transfer id is gated by `isHexId()` (exactly 64 lowercase hex chars) before it can reach `baseDir.resolve(id)`. `handleFileOffer`/`Chunk`/`Error` reach a `Path` only via an already-registered transfer. Verified; do not "harden" again. |
| **FIO01-J** Create files with appropriate access permissions | **GAP (minor)** — the relay spool and the two activity logs are created at the process umask. Relayed file content sits in that spool; `0700` on the directory would be tighter. |
| **FIO13-J** Do not log sensitive information outside a trust boundary | Ongoing concern. This is the same family as the `readme.html` leak of the real domain into published release jars — see `.github-sync/`. |
| **MSC02-J** Generate strong random numbers | **PASS** — `SecureRandom`; no `Math.random()` anywhere. |
| **MSC03-J** Never hard-code sensitive information | **PASS** |
| **IDS00-J** Prevent SQL injection | **N/A** — no direct SQL. |
| **OWASP** Validate at the boundary; secure defaults; fail closed | **PASS** — AV ships off; when enabled it fails closed at both ends; peer-approval defaults to allowlist. |
| **XSS (admin UI)** | **PASS** — `escHtml`, `jsArg` and `cssEscape` cover HTML, JS-attribute and selector contexts respectively. Better separated than most codebases; don't collapse them into one escaper. |

### C. Concurrency
*CERT VNA/LCK/THI/TSM, Effective Java ch. 11*

| Rule | Status here |
|---|---|
| **VNA02-J / VNA03-J** Compound operations on shared state must be atomic | **PASS** — no check-then-act (`containsKey` then `put`) patterns; concurrent collections used throughout. |
| **LCK09-J** Do not perform blocking operations while holding a lock | **JUSTIFIED** — `FileActivityLog` does file I/O inside `synchronized`. Deliberate and documented in the class javadoc: volume is a few records per transfer, and the lock is what stops an append interleaving with a prune's rewrite. |
| **LCK00-J** Use private final lock objects | Review item — `synchronized(this)` on `Transfer` is reachable only from the manager. |
| **THI03-J** Always call `wait()`/`await()` in a loop | **N/A** |
| **EJ Item 78** Synchronize access to shared mutable data | **PASS** |
| **EJ Item 80** Prefer executors and tasks to threads | **PASS** |

### D. API and object design
*Effective Java ch. 2–6, CERT OBJ/MET*

| Rule | Status here |
|---|---|
| **MET00-J** Validate method arguments | Mixed — public entry points from the network validate; internal helpers assume. Acceptable, but the boundary should be obvious from the javadoc. |
| **OBJ01-J / EJ Item 15** Minimize accessibility | **PASS** — package-private is used deliberately (`FileActivityLog`, `FileRelayStore`). |
| **OBJ05-J / OBJ13-J** Do not return references to private mutable members | Review item wherever a getter returns a collection. |
| **EJ Item 17** Minimize mutability | **PASS** — `record` used for value types (`ScanLogEntry`, `StoredFile`, `Row`). |
| **EJ Item 62** Avoid strings where other types are more appropriate | **GAP** — the strongest design gap here. `stage` is `"egress"`/`"ingress"`, `verdict` and `reason` are `String`, the servlet dispatches on `String action`. Each should be an `enum`; the compiler would then catch a typo that currently ships as a silently-wrong badge in the admin UI. |
| **MET09-J** `equals()` implies `hashCode()` | **PASS** — records generate both. |
| **EJ Item 54** Return empty collections, not null | **PASS** |

### E. Readability and maintainability
*Sonar, Google, Oracle*

| Rule | Status here |
|---|---|
| **S1192** String literals should not be duplicated | **GAP** — `"destination"` ×25, `"origin"` ×23, `"federation"` ×23, `"domain"` ×23, `"remote"` ×15, `"ingress"` ×9. Worth taking seriously: in the large-scale Sonar fault-prediction study, S1192 was the **single strongest predictor** of faults among 174 rules, despite being classified as a minor code smell. These are XML attribute names — they belong in constants. |
| **Cognitive complexity** | **Acceptable** — 14 methods over 60 lines, longest ~115 (`injectPresence`). The 642-line `doPost` god-method was removed in 1.10.1. |
| **S125** No commented-out code | **PASS** — zero. |
| **S1135** No `TODO`/`FIXME` tags | **PASS** — zero across Java and JS. Notable; these are the #1 and #2 most common findings in Sonar's own corpus. |
| **S1128** No unused imports | **GAP (trivial)** — 2: `java.util.Map` in `S2SMonitor`, `MultiUserChatService` in `FederationIQHandler`. |
| **S3740** No raw types | **PASS** |
| **Naming conventions** | **PASS** |
| **Parameterized logging** | **PASS** — `Log.debug("… {}", x)` throughout; zero concatenated log calls. |
| **Javadoc with `@param`/`@return`/`@throws`** | Partial — class-level javadoc is unusually good and explains *why*; method-level tags are sparse. |

### F. Professional obligations that show up in the code
*ACM Code of Ethics; ACM/IEEE-CS Software Engineering Code of Ethics*

These are not style. Each has a specific, checkable code consequence.

| Principle | Code consequence | Status here |
|---|---|---|
| **ACM 1.2 — Avoid harm** | Security controls fail closed, not open. A scanner that errors must not be treated as a clean result. | **PASS** — both AV gates fail closed, by explicit decision. |
| **ACM 1.3 — Be honest and trustworthy** | The UI must never claim something the code did not do. A file shown as "scanned" must have been scanned. | **PASS** — this is exactly why the `stage` column was added in 1.10.2: "scanned" was ambiguous about *which end*. |
| **ACM 1.6 — Respect privacy** | Do not log message bodies or user content. Log metadata, ids and sizes. | **PASS** — file names and ids are logged; bodies are not. |
| **ACM 1.7 — Honor confidentiality** | Internal infrastructure detail must not ship in artifacts. | **GAP** — real domain present in `src/main/resources/readme.html`, which ships inside the plugin jar. Mitigated by building releases from the sanitized clone; the durable fix is to sanitize the source. |
| **ACM 2.5 / SE 3.10 — Adequate testing and thorough evaluation** | Automated tests proportionate to risk. | **GAP — the largest one.** No `src/test` at all across ~12.8k lines. Refactor verification here is static (diffing action labels, JSON keys, emitted strings) because nothing can be executed. |
| **SE 3.11 — Adequate documentation, including known limitations** | Document what does *not* work, not only what does. | **PASS** — `docs/` plus in-code "known gap" notes. |
| **SE 6.07 / 8.02 — Be accurate about your software** | Release notes state what was verified and what was not. | **PASS** — "not live-tested" is recorded rather than glossed. |

---

## Ranked action list for this repo

Ordered by (fault-proneness × effort). Nothing here is a live defect; these are compliance gaps.

1. **Introduce enums for `stage`, `verdict` and `reason`** (EJ Item 62) — highest design value; converts a class of silent UI bugs into compile errors.
2. **Hoist duplicated XML attribute-name literals into constants** (S1192) — the strongest empirical fault predictor on the list.
3. **Sanitize peer-supplied strings before logging** (IDS03-J) — 2 call sites, small fix, closes log forging.
4. **Harden `SAXReader` against XXE** (IDS17-J) — 3 lines, normative rule, low practical risk.
5. **Narrow the 97 broad catches** (ERR07-J) — highest total effort; do it file-by-file during other work, not as one sweep. `FederationManager` first.
6. **Remove 2 unused imports** (S1128) — trivial.
7. **Restrict spool/log file permissions to `0700`** (FIO01-J) — one call at creation.
8. **Add a test source root** (ACM 2.5 / SE 3.10) — blocked offline: JUnit 5 is not in the local `~/.m2`, and adding it needs network access that the `mvn -o` fleet builds deliberately avoid. Highest value once unblocked; start with `FederationApiServlet`'s reply helpers and `FederationRoutingTable`'s Bellman-Ford.

## Sources

- [Oracle — Code Conventions for the Java Programming Language: Naming](https://www.oracle.com/java/technologies/javase/codeconventions-namingconventions.html)
- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- [Effective Java, 3rd Edition — Joshua Bloch](https://www.oreilly.com/library/view/effective-java-3rd/9780134686097/)
- [SEI CERT Oracle Coding Standard for Java](https://cmu-sei.github.io/secure-coding-standards/sei-cert-oracle-coding-standard-for-java/) · [full rule list](https://cmu-sei.github.io/secure-coding-standards/sei-cert-oracle-coding-standard-for-java/rules/)
- [OWASP Secure Coding Practices Quick Reference Guide](https://owasp.org/www-project-secure-coding-practices-quick-reference-guide/)
- [SonarSource — Top issues found in Java projects](https://www.sonarsource.com/blog/top-issues-in-java-projects/)
- [Lenarduzzi et al. — Some SonarQube issues have a significant but small effect on faults](https://arxiv.org/pdf/1908.11590) · [Are SonarQube Rules Inducing Bugs?](https://arxiv.org/pdf/1907.00376)
- [ACM Code of Ethics and Professional Conduct](https://www.acm.org/code-of-ethics)
- [ACM/IEEE-CS Software Engineering Code of Ethics](https://www.computer.org/education/code-of-ethics) · [full text](https://cs.pomona.edu/~michael/courses/csci190f20/papers/ethics.pdf)
