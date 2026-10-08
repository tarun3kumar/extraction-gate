# Test specification

| Field | Value |
| --- | --- |
| TestID | T-01 |
| Title | Extraction regression gate |
| Level | integration |
| Risk addressed | R1 hallucinated content, R2 missed or suppressed information, R3 silent regression |
| Steps | - Validate every baseline and release-candidate record against the extraction-record JSON Schema.<br>- Compare each field of baseline and release candidate with the expected results: status first (known, unknown, conflict), then the value: exact for coded fields, token overlap for free text.<br>- Compare the release candidate with the baseline: regressions and improvements.<br>- Apply the blocker rules to the release candidate and write the report. |
| Expected result | Pass (GO, the test passes) only when the release candidate has no blocker: every record schema-valid; no hallucinated, missed or wrong value on a critical field; no suppressed conflict; no missing allergy; no regression against the production baseline on any field. An invalid baseline record is shown under COMPARED WITH BASELINE and does not block. Acceptable differences are reported but do not fail the test. |
| Priority | P0 |
| Automation plan | now |

## What "pass" means for a non-deterministic extractor

The extractor is LLM-based, so the same document can produce different wording and ordering from run to run.
The gate is strict where a difference changes what a clinician reads and tolerant where it does not:

- **Strict on state and coded values.** The status of every field (known, unknown, conflict) must match the
  expected results, and coded values (grade, stage, R status, MMR status, TNM, dates, node counts, allergies) must
  match exactly.
  A hidden conflict always blocks, because it removes the cue that a human must check the source.
- **Tolerant on wording and order.** Free text matches when its content tokens overlap enough; lists match item
  by item in any order; a number sent as a string still counts as the right value (the type error is reported
  once, as a schema violation).
- **Compared with the production baseline.** A field that 1.4.0 gets right and the release candidate gets wrong
  blocks the release, whatever the field. A defect that production already has does not excuse the release
  candidate; it is judged by the same rules. So the gate answers "is this release at least as safe as production, and safe on its own terms?".

## Where the gate runs and what it blocks

- **Pull requests:** a required check on every pull request that touches prompts, extractor code, the schema or
  the model gateway configuration. A NO-GO blocks the merge.
- **Nightly:** against the live model, on freshly recorded outputs, to catch drift in the model or the gateway
  that no pull request caused. A NO-GO opens an incident ticket for the extraction team.
- **Release:** a release candidate is promoted only with a GO from the gate on that exact build; the report is
  kept with the release record.
