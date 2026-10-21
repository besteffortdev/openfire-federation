# Java code standards for this project

Three parts that answer three different questions:

- **Part 1 — what goes wrong.** The ranked list of the most-cited complaints about AI-generated
  Java (2022–2026), built first and used for the 1.10.1 refactor pass. Failure-driven.
- **Part 2 — what good looks like.** The widely-agreed Java canon — Oracle, Google, *Effective
  Java*, SEI CERT, OWASP, SonarSource — plus the professional-ethics codes that have concrete
  code-level consequences. Rule-driven.
- **Part 3 — the house rules.** What this repo does about comments, readability and error
  handling, each rule with its source, as applied in the 1.11.4 pass. Policy-driven.

Part 1 tells you what to look for in a review. Parts 2 and 3 tell you what to write in the first place.
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
| **ERR00-J** Never suppress or ignore a checked exception | **JUSTIFIED** — 13 `catch (… ignored)` blocks. All are genuine best-effort cleanup (`Files.deleteIfExists`, `socket.close`) or a parse whose failure has a defined fallback. Google's rule allows this *if the exception variable is named `ignored`/`expected`* — which it is — and asks for a comment saying why; since 1.11.4 each one has that one-line reason. |
| **ERR07-J** Do not throw `RuntimeException`, `Exception`, or `Throwable` (Sonar S112) | **PASS** — none left. The last, `FederationIQHandler.parsePacket`, went in the 1.11.4 restructure. |
| **ERR08-J** Do not catch `NullPointerException` or any of its ancestors (Sonar S2221) | **PASS since 1.11.4** — no `catch (Exception)` or `catch (Throwable)` remains. A barrier is spelled `catch (RuntimeException)` or as an explicit multi-catch, and sits only where Part 3 allows one. See also the note below. |
| **Sonar S1166** Exception handlers must preserve the original exception | **PASS since 1.11.4** — every handler that logs passes the exception object itself, so the stack trace and cause chain reach the log. Zero `e.getMessage()`-only log calls. |
| **Sonar S2139** Log or rethrow, not both | **PASS** |
| **Sonar S2142** Do not swallow `InterruptedException` | **PASS** — all 4 handlers restore the interrupt flag. |
| **ERR01-J / IDS15-J** An exception must not expose sensitive info across a trust boundary | **PASS since 1.11.4** — peers only ever see drop-and-log; the admin API answers an unexpected failure with HTTP 500 and a fixed message, and the stack trace goes to the Openfire log. |
| **ERR03-J** Restore prior object state on failure (EJ Item 76, failure atomicity) | **PASS** in the relay: a failed transfer deletes the part file, tombstones, and fails parked requests as one unit. **And at plugin start since 1.11.4:** a start that throws is rolled back before the failure reaches Openfire (Part 3). |
| **ERR04-J / ERR05-J** Never complete abruptly from `finally` | **PASS** |
| **EJ Item 69** Use exceptions only for exceptional conditions | **PASS** |
| **EJ Item 75** Include failure-capture information in the detail message | Mostly pass — log messages carry the id/peer/reason. |

#### On the "97 broad catches" — a correction to this document's first draft

The original audit ranked this the highest-effort remaining gap. Classifying all 97 before touching
any of them showed that was wrong, and the number itself was the misleading part:

| Category | Count | Verdict |
|---|---|---|
| Guards a whole method that calls into Openfire internals from a plugin thread | 10 | **Must stay broad.** An exception escaping `finalizeReceive`, `findLocalMucRoom` or `syncLocalRosterOnSubscriptionRelay` reaches an executor or an interceptor, where it kills the task or breaks presence relay. |
| Guards a `ScheduledExecutorService` task body | 3 | **Must stay broad** — and this is not merely permitted but *required* by CERT **TPS03-J**, "ensure that tasks executing in a thread pool do not fail silently". An uncaught exception cancels the task permanently. The code already says so in a comment. |
| Guards 2–20 lines of pure in-memory or Openfire-API work | 64 | **Nothing to narrow to.** These call methods that declare no checked exception; "narrowing" would mean `catch (RuntimeException)`, which is not an improvement. |
| Per-item barrier inside a loop (one bad user/room must not abort the batch) | several | Deliberate. |
| Genuinely narrowable — guards I/O or parsing with a real checked exception | ~6 | 4 fixed in 1.10.5. |

So the honest read is that this codebase is *already* close to compliant with ERR07-J, and a
mechanical sweep would have made it worse by removing isolation barriers that a plugin needs.
The remaining two candidates were examined and deliberately left broad:

- `FileRelayStore.init()` declares `IOException`, but narrowing it turns a graceful "file
  federation disabled, everything else keeps working" into a plugin-load failure.
- `ClamAvClient.scan()` must return a `Verdict` on *any* failure — that is what makes the AV gate
  fail closed. A narrower catch would let a `RuntimeException` escape and skip the scan-log row.

**Lesson worth keeping:** a linter count of "broad catches" is a prompt to look, not a defect
total. In a server plugin, the boundary where your code is called by someone else's event loop is
exactly where a broad catch belongs.

**Update, 1.11.4.** The barriers stayed; what changed is how they are written. Around code whose
only checked exception is `IOException`, `catch (Exception)` catches exactly what
`catch (IOException | RuntimeException)` catches, so the two cases above were rewritten that way
without losing the "never escapes" property, and the code now says which failures it expects. The
same went for every other site: a barrier is `catch (RuntimeException)`, and anything with a checked
exception names it. Part 3 lists where barriers are allowed.


### B. Security and trust boundaries
*CERT IDS/FIO/SEC/MSC, OWASP*

| Rule | Status here |
|---|---|
| **IDS03-J** Do not log unsanitized user input (log forging) | **FIXED in 1.10.4** — peer-supplied `reason`, `requester` and `url` reached `Log.*` verbatim at 8 call sites in `FileRelayManager`. XML attribute-value normalization folds a literal newline to a space, but a `&#10;` character reference survives it, so a peer could forge log lines. Now routed through `LogSafe.text()`. *(The first audit also named `FederationIQHandler:1961`; that was a false positive — both of its callers pass string literals, so nothing untrusted reaches it.)* **Extended in 1.11.4:** an inbound action whose routing values contain a control character is dropped before dispatch ([09-validation.md](protocol/09-validation.md#1b-routing-values), rule 1b), and the `FED-LEAK` warnings no longer embed the stanza XML, which is now logged at DEBUG through `LogSafe`. |
| **IDS17-J** Prevent XML external entity attacks | **FIXED in 1.10.4** — `FederationFileConfig` used a bare `new SAXReader()`. Input is admin-owned `conf/openfire.xml` so practical risk was low, but the rule is normative; DOCTYPE declarations and both external-entity classes are now refused. |
| **IDS16-J** Prevent XML injection | **PASS** — stanzas are built through dom4j `Element`/`addAttribute`, never string-concatenated. |
| **FIO16-J** Canonicalize path names before validating them | **PASS** — every remote-supplied transfer id is gated by `isHexId()` (exactly 64 lowercase hex chars) before it can reach `baseDir.resolve(id)`. `handleFileOffer`/`Chunk`/`Error` reach a `Path` only via an already-registered transfer. Verified; do not "harden" again. |
| **FIO01-J** Create files with appropriate access permissions | **FIXED in 1.10.5** — the relay spool is now forced to `rwx------` on every `init()` and on a store relocation, not just at creation, so an existing loose directory gets tightened too. Deliberately **not** extended to the two activity logs: they live in Openfire's `logs/` beside `openfire.log`, hold metadata rather than content, and are meant to be tailed and shipped by whatever the operator already runs there. |
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
| **EJ Item 62** Avoid strings where other types are more appropriate | **FIXED in 1.10.4, in part.** `stage` → `RelayStage`, rejection `reason` → `RejectionReason`. The payoff was larger than expected: each rejection site used to spell *two* unchecked strings — a log code (`"AV_INFECTED"`) beside a wire code (`"av-infected"`) — and nothing verified they agreed. `RejectionReason` now carries both, and `PERMANENT_REJECT_REASONS` is derived from it instead of restated. **Deliberately not converted:** `verdict`, because it is only ever written from `ClamAvClient.Verdict.name()` — there is no loose string to mistype — and `Verdict` is package-private to the AV client, so publishing it through a record read by the servlet would widen that client's API for no gain. Likewise the servlet's `String action`: that is the browser's wire contract, not an internal representation, and Item 62 is about the latter. |
| **MET09-J** `equals()` implies `hashCode()` | **PASS** — records generate both. |
| **EJ Item 54** Return empty collections, not null | **PASS** |

### E. Readability and maintainability
*Sonar, Google, Oracle*

| Rule | Status here |
|---|---|
| **S1192** String literals should not be duplicated | **FIXED in 1.10.4** for the protocol vocabulary — ~104 occurrences of `origin`, `destination`, `via`, `remote`, `local`, `ts`, `id` and the `federation` element now resolve to named constants on `FederationStanzaFactory`. Worth taking seriously: in the large-scale Sonar fault-prediction study S1192 was the **single strongest predictor** of faults among 174 rules, despite being rated a minor smell. Two collisions were found and deliberately *not* merged: `"federation"` also names an unrelated `openfire.xml` config block, and `"id"` also names a XEP-0060 PEP `<item/>` id. Hoisting those together would have coupled vocabularies that are free to diverge. |
| **Cognitive complexity** (Sonar S3776, S138) | **Improved in 1.11.4** — methods over 60 lines went from 32 to 11, and the 2,506-line `FederationManager` and 2,598-line `FederationIQHandler` are split along the protocol chapters (class map in Part 3). The longest method is now `peerAction`, 116 lines of one flat `switch` over admin actions. The 642-line `doPost` god-method was removed in 1.10.1. |
| **S125** No commented-out code | **PASS** — zero. |
| **S1135** No `TODO`/`FIXME` tags | **PASS** — zero across Java and JS. Notable; these are the #1 and #2 most common findings in Sonar's own corpus. |
| **S1128** No unused imports | **FIXED in 1.10.5** — both removed; two more in 1.11.4. |
| **S1144** No unused private code | **FIXED in 1.11.4** — 7 unused methods removed, including three stanza builders the restructure left behind. |
| **Duplication** | **FIXED in 1.11.4** — four domain and JID validators became one (`FederationNames`); every send goes through `FederationSender.send` (41 call sites) instead of repeating its own route-then-catch block; three near-identical 1:1 forwarders became one; the admin feed's two mapping-health blocks became one. |
| **S103** Line length | **PASS since 1.11.4** — 120 columns everywhere; the 42 longer lines were wrapped. |
| **S2208** No wildcard imports; no fully-qualified class names in code | **PASS since 1.11.4** |
| **S3740** No raw types | **PASS** |
| **Naming conventions** | **PASS** |
| **Parameterized logging** | **PASS** — `Log.debug("… {}", x)` throughout; zero concatenated log calls. |
| **Javadoc with `@param`/`@return`/`@throws`** | **By policy since 1.11.4** — every class has a one-to-three-sentence Javadoc and every method that is not self-explanatory a one- or two-line summary. Tags are used only where they add information (Google 7.3.1); see Part 3. |

### F. Professional obligations that show up in the code
*ACM Code of Ethics; ACM/IEEE-CS Software Engineering Code of Ethics*

These are not style. Each has a specific, checkable code consequence.

| Principle | Code consequence | Status here |
|---|---|---|
| **ACM 1.2 — Avoid harm** | Security controls fail closed, not open. A scanner that errors must not be treated as a clean result. | **PASS** — both AV gates fail closed, by explicit decision. |
| **ACM 1.3 — Be honest and trustworthy** | The UI must never claim something the code did not do. A file shown as "scanned" must have been scanned. | **PASS** — this is exactly why the `stage` column was added in 1.10.2: "scanned" was ambiguous about *which end*. |
| **ACM 1.6 — Respect privacy** | Do not log message bodies or user content. Log metadata, ids and sizes. | **PASS since 1.11.4** — file names and ids are logged; bodies are not. *Correction:* until 1.11.4 the `FED-LEAK` warnings embedded the whole stanza, body included; that XML is now DEBUG only. |
| **ACM 1.7 — Honor confidentiality** | Internal infrastructure detail must not ship in artifacts. | **GAP** — real domain present in `src/main/resources/readme.html`, which ships inside the plugin jar. Mitigated by building releases from the sanitized clone; the durable fix is to sanitize the source. |
| **ACM 2.5 / SE 3.10 — Adequate testing and thorough evaluation** | Automated tests proportionate to risk. | **PARTIAL — started in 1.11.1.** `mvn -o test` now runs 76 tests across 11 classes: room fan-out on rings and meshes and its duplicate warning (1.11.2), the routing table's trust and metric rules, the admin feed's JSON escaping, the spool's transfer-id and file-name gates, room-default globs, `LogSafe` (ported from the sister repos), and since 1.11.4 the inbound control-character gate, the domain and JID checks, and best-effort sending. Each rule was checked by mutation: breaking it fails a test. A live harness, kept outside the repository, covers the rest end to end against a 7-server lab. Still without unit tests: the IQ handler, the packet interceptor and the file-relay state machine, which is where the 1.10.6 defects were. |
| **SE 3.11 — Adequate documentation, including known limitations** | Document what does *not* work, not only what does. | **PASS** — `docs/` plus in-code "known gap" notes. |
| **SE 6.07 / 8.02 — Be accurate about your software** | Release notes state what was verified and what was not. | **PASS** — "not live-tested" is recorded rather than glossed. |

---

## Part 3 — House rules for comments, readability and error handling

Written for the 1.11.4 pass, which applied them to every Java file. Each rule names its source; where
the sources disagree, the choice made here is stated.

### Comments

| Rule | Source |
|---|---|
| Every class opens with a Javadoc of one to three sentences: what it is for and where it fits. | Google 7.3 |
| A method gets a one- or two-line summary fragment ("Returns…", "Whether…") unless it is self-explanatory, like a plain getter. | Google 7.2, 7.3.1 |
| `@param`, `@return` and `@throws` only when they say something the summary does not. | Google 7.3.1 |
| An inline comment explains a non-obvious *why*, in one line. It never restates what the code says. | Atwood, "Code tells you how, comments tell you why"; *Clean Code* ch. 4 |
| No banner or section-marker comments, no change history, no commented-out code. History lives in git and in `docs/`. | *Clean Code* ch. 4; Sonar S125 |
| An empty `catch` names its variable `ignored` and says in one line why doing nothing is right. | Google 6.2; CERT ERR00-J |

Applied: comment lines went from 4,071 to 1,550 (25.9% to 11.7% of the Java), and `//` lines from 988
to 117. The comment pass on its own was checked by comparing compiled bytecode before and after
(`javap -c -p`) for every class: identical, so it changed nothing but comments. The admin page's
JavaScript was checked the same way through its token stream (11,992 tokens, identical).

### Readability

| Rule | Source |
|---|---|
| A class has one job, and is named for it. Here the protocol classes follow the chapters of `docs/protocol/` (class map below). | *Clean Code* ch. 10 |
| A method does one thing. Guard clauses and early returns instead of nesting; a long method is split at its natural steps. | Sonar S3776 (cognitive complexity), S138 |
| Names say what a thing is: `downloadToPart`, `passesEgressScan`, `dropRoutesThrough`. | *Clean Code* ch. 2 |
| No duplication: a rule that exists twice drifts apart. | Sonar S1192; DRY, weighed against KISS as noted in Part 2 |
| No dead code, unused imports, wildcard imports or fully qualified names in code. | Sonar S1144, S1128, S2208 |
| Lines of at most 120 columns. Google uses 100; 120 is this repo's existing width and IntelliJ's default. | Sonar S103 |

### Error handling

| Rule | Source |
|---|---|
| Catch the narrowest type the code can throw; name checked exceptions instead of catching `Exception`. | CERT ERR08-J; Sonar S2221 |
| A broad `catch (RuntimeException)` is a deliberate isolation barrier, allowed only where an escaping exception would do more harm than the failure itself (list below). | CERT ERR08-J, TPS03-J |
| Log the exception object, not its message, so the stack trace and cause survive. | Sonar S1166 |
| Log or rethrow, never both. | Sonar S2139 |
| Restore the interrupt flag when catching `InterruptedException`. | Sonar S2142 |
| Never declare `throws Exception`. | CERT ERR07-J; Sonar S112 |
| Outside callers get a generic error; the details go to the log. | CERT ERR01-J; OWASP Error Handling Cheat Sheet |
| A failed operation leaves no half-done state behind: undo it, then report it. | CERT ERR03-J; *Effective Java* item 76 |
| Untrusted values are checked before they reach the log. | CERT IDS03-J; OWASP Logging Cheat Sheet |

**Where barriers are allowed.** Openfire already isolates some callbacks, which was checked in the
5.0.5 bytecode: `IQHandler.process` catches `Exception`, logs it and answers `internal-server-error`;
`InterceptorManager` catches `Throwable` from each interceptor; and `PresenceEventDispatcher` and
`MUCEventDispatcher` wrap each listener call. The plugin adds its own barrier only where one of those
is not enough:

- **Executor and scheduled task bodies** — an exception escaping a scheduled task cancels it for
  good (TPS03-J). Examples: the S2S poll, keepalive and reconnect tasks, the file relay's sweep and
  purge, contact-list group syncs.
- **One step of a listener or interceptor callback** — so a failed step does not skip the next one
  (for example, a failed flush of buffered room deliveries still lets federated moderations replay).
- **One item of a loop** — one bad room, user or destination must not abort the batch.
- **Sending** — `FederationSender.send` logs a failed send with its cause and returns `false`, so one
  lost stanza never aborts a fan-out or a teardown.
- **The admin API** — an unexpected failure answers HTTP 500 with a fixed message.
- **Plugin start** — Openfire registers a plugin *before* calling `initializePlugin` and keeps it
  registered when that throws (checked in `PluginManager.loadPlugin`), so the hooks of a half-started
  plugin would stay live until it is unloaded. `FederationPlugin` now stops whatever had started,
  attaches any cleanup failure to the original with `addSuppressed`, and rethrows.

### Class map

| Class | Responsibility | Spec |
|---|---|---|
| `FederationPlugin` | Openfire entry point; start, stop, and rollback of a failed start | — |
| `FederationManager` | Owns the subsystems and Openfire hooks; carries out peer, mapping, gossip and trust actions | 02, 03, 04 |
| `PresenceRelay` | Local users' presence and PEP data to their remote contacts | 06, 08 |
| `OccupantSync` | Virtual occupants: syncing rosters to mapped rooms and evicting the unreachable | 05 |
| `MappingProbe` | `mapping-ping` / `mapping-pong` end-to-end probes | 04 |
| `CertificatePinning` | Trust on first use for peers' S2S keys | [security.md](security.md) |
| `FederatedDirectory` | The XEP-0055 search service over received lists made searchable (1.12.0) | 08 |
| `RemoteSearchGuard` | Refuses user searches from servers the admin did not allow (1.12.0) | [security.md](security.md) |
| `FederationNames` | The local domain and the domain and JID syntax checks | 09 §7 |
| `protocol.FederationIQHandler` | Dispatch; peering, routing, room advertisements, mapping lifecycle, probes, contact lists, files | 02–04, 07, 08 |
| `protocol.MucTrafficHandler` | `muc-forward`: relaying and injecting room traffic | 05 |
| `protocol.DirectTrafficHandler` | `direct-forward`, `presence-forward`, `iq-forward`, PEP answers | 06 |
| `protocol.InboundValidation` | The inbound checks, in the order the spec gives | 09 |
| `protocol.FederationSender` | Best-effort sending and local delivery | 01 |
| `protocol.FederationStanzaFactory` | Builds every federation stanza | 01–08 |
| `protocol.FederationPacketInterceptor` | Outbound interception: room forwarding, 1:1 relays, leak guard | 05, 06 |
| `RoomEventListener` | MUC events: room defaults, buffered deliveries, accepted messages (was `RoomCreationListener`) | 04, 05 |

### How 1.11.4 was verified

- **Comments:** bytecode identical before and after the comment pass (above).
- **Stanza builders:** every builder's XML output compared between the old and the new
  `FederationStanzaFactory`, 37 cases: identical. Since the wire format is that output, the protocol
  did not move.
- **Unit tests:** 76, up from 66, all passing.
- **Live:** the end-to-end harness run against the 7-server lab before and after the deploy. Every
  test gave the same result except two 1:1 checks, and those depend on which of the recipient's open
  sessions Openfire delivers a bare-JID message to: it picks the most recently active one, and a
  session counts as active when it *receives* a stanza too. Run without the extra sessions, they
  matched as well, and the server logs show those messages relayed and delivered exactly as before.
- **Logs:** after the deploy no server logged a WARN or ERROR that it had not logged at earlier restarts.

---

## Ranked action list for this repo

Ordered by (fault-proneness × effort). Nothing here is a live defect; these are compliance gaps.

**Done in 1.10.4:**

1. ~~Introduce enums for `stage` and `reason`~~ (EJ Item 62) — done; `verdict` and the servlet's `action` deliberately left as strings, see section D.
2. ~~Hoist duplicated protocol literals into constants~~ (S1192) — done, ~104 sites.
3. ~~Sanitize peer-supplied strings before logging~~ (IDS03-J) — done, 8 sites via `LogSafe`.
4. ~~Harden `SAXReader` against XXE~~ (IDS17-J) — done.

**Done in 1.10.5:**

5. ~~Narrow the broad catches~~ (ERR07-J) — **re-scoped after measurement.** 4 narrowed; the rest are deliberate isolation barriers, 3 of them required by CERT TPS03-J. See the correction note in section A — the "97" figure was misleading and a mechanical sweep would have been a regression.
6. ~~Remove unused imports~~ (S1128) — done.
7. ~~Restrict spool permissions~~ (FIO01-J) — done, spool only, logs deliberately excluded.

**Done in 1.10.6** (from an external review; these *were* live defects, not compliance gaps):

8. ~~File relay ids acted as bearer authorization~~ — a per-share capability (`token`) now gates
   `file-request`, plus a route-back binding on the claimed requester and the untrusted-exposure gate
   applied to the local branch of `handleFileRelay`. See [security.md](security.md#per-share-file-capability).
9. ~~Overlay envelopes were not bound to their embedded recipient~~ — `deliverableHere` rejects a stanza
   addressed off-server at the final hop, closing a confused-deputy relay for configured peers.
10. ~~A half-started relay kept rewriting URLs~~ — explicit `available` state; an unusable store or
    unmounted endpoint now degrades to leaving the original URL alone, and is recoverable without a restart.
11. ~~Staging had no absolute deadline and shared a pool with the sweep meant to time it out~~ — split pools,
    absolute budget, cancellable connections, bounded shutdown.
12. ~~A `file-error` mid-stream did not abort the receive~~ — recipients were told "rejected" for a file that
    then downloaded fine.
13. ~~The server-wide S2S idle timeout was never restored~~ — saved and given back on stop, with
    compare-and-set so an administrator's own value survives.

**Done in 1.11.4** (the presentation pass; rules in Part 3):

14. ~~Comments cut to a class summary and one or two lines per method~~ — comment lines 4,071 → 1,550.
15. ~~Split the god classes~~ — `FederationManager` and `FederationIQHandler` split along the protocol
    chapters; methods over 60 lines 32 → 11.
16. ~~Spell every barrier precisely~~ (ERR08-J, S2221) — no `catch (Exception)` left; exceptions logged with
    their stack traces (S1166); empty catches give their reason (ERR00-J).
17. ~~Undo a failed plugin start~~ (ERR03-J).
18. ~~Generic 500 from the admin API~~ (ERR01-J).
19. ~~Drop inbound routing values with control characters~~ (IDS03-J) — 09-validation rule 1b.
20. ~~Keep stanza XML out of WARN lines~~ (ACM 1.6).
21. ~~Remove duplication and dead code~~ (S1192, S1144).

**Remaining:**

22. ~~Add a test source root~~ (ACM 2.5 / SE 3.10) — **started in 1.11.1.** The
    offline blocker is gone: JUnit 5.10.2 and surefire 3.2.5 are in `~/.m2` from the sister builds. Two
    things had to change besides the pom. `.gitignore`'s `test/` (meant for the top-level Python
    `test/`) also matched `src/test/`, so it is now anchored as `/test/`. And `updateFromPeer` gained a
    package-private overload that takes the local domain, because `XMPPServer` cannot be stood up in a
    unit test. See section F for what is covered.
23. **Extend tests to the state machines** — `deliverableHere`, the file-relay transfer lifecycle and
    the `file-error` abort path. These are the 1.10.6 defect sites and need either seams like the one
    above or a fake `XMPPServer`.

### How 1.10.4 was verified, before there were tests

Worth recording, because it is the pattern to reuse for anything the tests do not reach yet
(item 23). The pre-change tree was built in a throwaway `git worktree` alongside the new one, and every string constant in the
five affected classes compared between the two:

- `FederationStanzaFactory`, `FederationIQHandler`, `FederationPacketInterceptor` and
  `FederationApiServlet` — **string constants identical**. Since the whole protocol is those
  literals, that is direct evidence the wire format and the admin-UI JSON did not move.
- `FileRelayManager` — the only deltas were the *duplicate* stage/reason literals, which is exactly
  what moving them into enums should remove.

The enums were then executed to confirm they reproduce the removed literals exactly
(`EGRESS.token()` → `egress`, `AV_INFECTED.wireReason()` → `av-infected`, …), and `LogSafe` was
exercised against forged newlines, CRLF, tabs, ANSI escapes, over-length input and Unicode. The
admin UI needed no change: its `REJECTION_REASON_LABELS` keys already match the enum constant names
and its stage comparisons already match `token()`.

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
- Robert C. Martin, *Clean Code* (Prentice Hall, 2008) — ch. 2 Meaningful Names, ch. 3 Functions, ch. 4 Comments, ch. 10 Classes
- [Jeff Atwood — Code Tells You How, Comments Tell You Why](https://blog.codinghorror.com/code-tells-you-how-comments-tell-you-why/)
- [SonarSource — Cognitive Complexity (G. Ann Campbell)](https://www.sonarsource.com/docs/CognitiveComplexity.pdf)
- SonarSource rules: [S1166](https://rules.sonarsource.com/java/RSPEC-1166/) · [S2139](https://rules.sonarsource.com/java/RSPEC-2139/) · [S2142](https://rules.sonarsource.com/java/RSPEC-2142/) · [S2221](https://rules.sonarsource.com/java/RSPEC-2221/) · [S3776](https://rules.sonarsource.com/java/RSPEC-3776/)
- [OWASP Error Handling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html) · [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
