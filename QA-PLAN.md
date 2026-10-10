# QA Plan — AI-Assisted Oncology Workflow

## 1. Assumptions

- **Intended purpose:** A clinician reviews every output and decides.
- **People:** a Regulatory owner decides the classification.
- **Environments:** no new QA environment; staging for integration and formal verification.
- **Tooling:** Java, TestNG and GitHub Actions

## 2. Risk-based thinking

R1–R3 are AI risks, R4 is a product risk, R5 and R6 are compliance risks. Each is rated 1–5, where 5 is the worst, for severity (S), likelihood (L) and detectability (D); Detectability 5 means unlikely to be caught before a clinician sees it.

| ID | Risk | S | L | D | Mitigation |
| --- | --- | --- | --- | --- | --- |
| R1 | Hallucination: a clinical fact that is not in the source<br>*S: can lead to wrong treatment*<br>*L: occasional*<br>*D: AI reasoning sounding plausible* | 5 | 3 | 4 | The quality gate blocks any value for a field that the documents leave open.<br>In chat answers, every clinical statement must point to its source |
| R2 | Missed or suppressed information: a documented field returned as unknown, or a conflict hidden<br>*S: changes the therapy*<br>*L: occasional*<br>*D: an absence is not visible* | 5 | 3 | 4 | The benchmark checks the status of each field (known, unknown, conflict), not only the value. One missed value or hidden conflict on a critical field blocks the release. |
| R3 | Silent regression after a model or prompt change<br>*S: many patients are affected at once*<br>*L: changes are frequent*<br>*D: nothing fails visibly* | 5 | 4 | 4 | The model is fixed to one exact version i.e. model pinning. Every model or prompt change runs the regression gate against production.<br>A nightly run reveals shifts when nothing was changed. |
| R4 | Streaming and reconnect: truncated or duplicated text after resume<br>*S: a statement lost or repeated*<br>*L: connections drop at times*<br>*D: partly visible* | 4 | 3 | 3 | A test cuts the network during a streamed answer and restores it. The text on screen must then equal the message stored on the server. Nothing is missed and nothing appears twice. |
| R5 | GDPR: a patient's name or other identifying detail reaches the LLM provider or the logs because pseudonymisation missed it, or a patient's data reaches another user<br>*S: legal and trust damage*<br>*L: several leak paths*<br>*D: invisible in the UI* | 4 | 3 | 4 | Test documents carry made-up marker values. A test searches all outgoing data and logs for them; one hit and quality gate fails.<br>A second test checks that no user can open another user's conversation. |
| R6 | MDR: evidence and traceability incomplete for the released build<br>*S: blocks the release*<br>*L: missing links/documentation due to human error*<br>*D: a trace check finds it* | 4 | 4 | 2 | Every test is linked to the requirement and risk it verifies, ex: `TestNG description` field in `ExtractionGate` test. CI produces the trace table and the evidence package on each build, so the documentation matches the release. |

**Tested first:** R1–R3, because they can harm patients and are the hardest to detect. Then R5, because a data leak cannot be undone, and R4. R6 is a process control that starts on day 1.

## 3. End-to-end test strategy

| Level | Automated | Manual |
| --- | --- | --- |
| Unit, Integration tests, e2e FE tests for chat interface | All, owned by engineers | None |
| Regression | Extraction benchmark and AI-output golden set | Clinician reviews a weekly sample |
| End-to-end and non-functional | Top 5 critical web UI journeys, with a deliberate network cut during streaming and reconnect | Weekly exploratory session with a clinician |
| Negative / misuse | Out-of-scope requests, injected documents, authorisation | Aggressive, security focussed tests on each release candidate |

**Deprioritised until after release:** full load tests, more browsers, accessibility, visual regression as none maps to a risk charted above.

### "Pass" for non-deterministic output

1. **Check the facts, not the wording.** We check field states, values, citations and safety flags, not the exact sentences.
2. **Score the meaning of free-text answers over several runs.** Each test case is run several times (e.g. 5), because the same question can get a different answer each time. A judge model, first checked against clinician ratings, scores every answer on correctness, completeness and safety. A release passes only if no run fails on safety and the average score is at least as high as production's.

### Extraction benchmark as CI gate

Critical fields are all except secondary diagnoses. Coded values match exactly; free text matches at a token overlap of 60% or more.

| Metric (errors counted) | Allowed |
| --- | --- |
| Schema violation in a record | 0 |
| Hallucinated, missed or wrong value on a critical field; missing allergy; suppressed conflict | 0 |
| Regression against production, any field | 0 without a signed waiver |

Any error above the allowed number blocks the release. The gate runs on every change to prompts, extractor or schema and nightly, and blocks release-candidate promotion.

### Top 5 prioritised tests

Following the Risk Based thinking, R1, R2 and R3 have highest ranking -

| Test | Level · Risk · Priority · Automation | Steps | Expected result |
| --- | --- | --- | --- |
| T-01 Extraction regression gate | integration · R1, R2, R3 · P0 · now | • Validate outputs against the schema<br>• Compare fields and states with the expected results<br>• Apply thresholds and write the report | No blocker in the gate table above |
| T-02 Stream resume integrity | e2e · R4 · P0 · now | • Start a streamed answer<br>• Drop and restore the network<br>• Compare with the stored message<br>• Automation: Selenium WebDriver switches Chrome offline mid-stream and back online, then compares the chat text with the message from the API | Text and metadata equal the stored message; no duplicate |
| T-03 No patient identity leaves un-pseudonymised | integration · R5 · P0 · now | • Send documents with made-up marker values<br>• Capture outgoing data, logs and traces<br>• Search them for the markers<br>• Automation: an API test that records every outgoing request; the test searches those requests and the logs for the markers | No marker past the boundary |
| T-04 Grounded answers and safety flags | integration · R1 · P0 · next | • Run golden questions several times<br>• Check the invariants on every answer<br>• Automation: a data-driven TestNG test runs each golden question five times; citations and flags are checked in code | No unsupported claim; safety flag in every trigger case |
| T-05 Patient isolation | contract · R5 · P1 · next | • Start a stream as user A<br>• As user B, request A's conversation<br>• Automation: API tests with two test users; user B's token is sent to every conversation and stream endpoint of user A | Every request rejected; nothing from A reaches B |

## 4. Regulatory alignment and audit-ready evidence

I assume MDR Class IIa and IEC 62304 Class B, because the software informs a decision that the clinician takes, hence considered lower risk. If it recommended therapy on its own, I would expect a higher class. For QA this means a plan, protocol and report per release, with every risk control verified.

| MDR technical documentation | QA role | Evidence |
| --- | --- | --- |
| Software lifecycle for medical device software (IEC 62304) | Owns | Test plan, protocols and reports per release; benchmark reports; change records |
| Risk management file for medical device (ISO 14971) | Contributes | AI failure modes for the hazard analysis; proof that each risk control works |
| Post-market surveillance | Contributes | Drift metrics and incident trends; each confirmed production issue becomes a regression case |

**GDPR test data.** Test environments use synthetic data only, and the smallest dataset each test needs; anonymised real documents are used only for the benchmark. Production data is never copied, so erasure requests do not reach test environments.

**Traceability.** Every automated test is tagged with the requirement id and risk it verifies. CI exports the requirement → test → evidence matrix per build; an unverified requirement blocks the release candidate.

**Release package.** Generated by CI: versions of code, model, prompts and schema; test and benchmark reports; trace matrix; updated risk file; known anomalies; approvals from QA, Regulatory and Clinical.

## 5. First 90 days

### Days 1–30: stabilise and measure

- Week 1 risk workshop with Engineering, Product, Clinical and Regulatory to confirm this register and the risk class assumption; I join refinement and PR review.
- T-01 required in CI, T-02 and T-03 on benchmark scores baselined.

### Days 31–60: automate and formalise

- Grow the extraction golden set from 3 to 50+ cases and build the AI-output golden set with the clinician; T-04 becomes a gate.
- Automate the traceability matrix and release package; formalise change control for model and prompt changes; add T-05.

### Days 61–90: mature and partner

- A confirmed production issue becomes a golden case; monthly quality review with Engineering, Product and Compliance.
- Train engineers to write evaluation cases; decide on a second QA engineer from the data.
