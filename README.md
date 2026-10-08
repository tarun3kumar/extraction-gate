# Extraction regression gate

A TestNG test that decides whether extractor 1.5.0-rc.1 may replace 1.4.0 in production. It compares the recorded
outputs of both versions with the expected results in `starter-pack/expected` and reports GO or NO-GO.

The QA plan for the whole product is in `QA-PLAN.md`; this gate is its test T-01.

## How to run

- `./gradlew test` runs the gate (JDK 25; the starter pack is included). A passed test is GO, a failed test is
  NO-GO, and an error also fails the test. The report goes to the console and to `build/gate-report.txt`.
- The CI workflow `.github/workflows/extraction-gate.yml` runs the same command on every push; the job fails on
  purpose for 1.5.0-rc.1.

## Design choices and thresholds

- **State before value.** The status (known, unknown, conflict) is compared first, then the values.
- **Strict on coded values, tolerant on wording.** Coded values such as grade, stage, dates, allergies etc must
  match exactly; free text matches at a token overlap of 0.6 or more. Example: in patient1's `surgery_procedure`,
  the release candidate says "lymph node dissection" instead of "lymphadenectomy" and drops "Elective"; 5 of 8
  words match (0.625), a borderline pass that shows why 0.6 needs calibration by a clinician.
- **Zero-tolerance blockers.** A schema violation in the release candidate; a hallucinated, missed or wrong value on
  a critical field; a suppressed conflict; any regression against 1.4.0. All fields are critical except
  `surgery_procedure` and `secondary_diagnoses`, where a defect is reported as an acceptable difference.

## Release recommendation

**NO-GO for 1.5.0-rc.1; production stays on 1.4.0.** The release candidate has three regressions on critical fields:
it misses dMMR in patient1, invents "FOLFOX chemotherapy" in patient2, and reports G3 where the patient3 documents
disagree between G2 and G3. It also sends patient3's positive node count as a string, a schema type error. A next
release candidate must fix those four and keep its one improvement, the date-of-birth conflict in patient2, which
1.4.0 misses.

```
EXTRACTION REGRESSION GATE: NO-GO
Release candidate 1.5.0-rc.1 vs baseline 1.4.0 (production)

BLOCKERS (4)
  patient1  mmr_status           MISSED, REGRESSION                  expected dMMR, got unknown
  patient2  adjuvant_therapy     HALLUCINATION, REGRESSION           expected unknown, got FOLFOX chemotherapy
  patient3  (record)             SCHEMA_VIOLATION                    $.fields.lymph_nodes.value.positive: string found, integer expected
  patient3  histologic_grade     SUPPRESSED_CONFLICT, REGRESSION     expected conflict {G2 / G3}, got G3

ACCEPTABLE DIFFERENCES (1)
  patient2  secondary_diagnoses  WRONG_VALUE                         expected 4 items, got 3 items (missing [Hyperlipidemia])

COMPARED WITH BASELINE 1.4.0 (4)
  patient1  mmr_status           REGRESSION                          dMMR -> unknown
  patient2  date_of_birth        IMPROVEMENT                         1967-03-15 -> conflict {1967-03-15 / 1976-03-15}
  patient2  adjuvant_therapy     REGRESSION                          unknown -> FOLFOX chemotherapy
  patient3  histologic_grade     REGRESSION                          conflict {G2 / G3} -> G3
```

## What I would add next

- Several runs per case, blocking on a critical failure in any run (today: one recorded run per case).
- A larger benchmark with expected results signed off by a clinician (today: one patient in three variants).
- Detecting values the output adds to a list (today: only missing values are detected).
- Every confirmed production issue becomes a new case in the benchmark (today: the three starter-pack cases).
