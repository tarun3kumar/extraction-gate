# Extraction starter pack

Material for section 6 of the take-home (extraction regression gate). All data is fully synthetic.

## Contents

| Path | What |
| --- | --- |
| `documents/patient1-3/` | Source documents for three patient cases (endoscopy report, pathology report, post-operative follow-up letter). Treat each folder as a separate case. |
| `schema/extraction-record.v1.schema.json` | JSON Schema (draft 2020-12) for one extracted record per case. |
| `expected/` | Expected extraction result (ground truth) per case. |
| `outputs/v1/` | Output of extractor `1.4.0`, the version currently in production. |
| `outputs/v2/` | Output of extractor `1.5.0-rc.1`, the release candidate. |

## Field states

Every clinical field in a record has one of three states:

- `known`: one value was extracted.
- `unknown`: the information is not documented.
- `conflict`: the documents disagree. All candidate values are listed with their sources (`docN.md:L<line>`).

```json
"surgery_date": {
  "status": "conflict",
  "candidates": [
    { "value": "2025-10-28", "sources": ["doc2.md:L8"] },
    { "value": "2025-10-27", "sources": ["doc3.md:L12"] }
  ]
}
```

The extractor is an LLM-based system, so wording and ordering in its output can vary between runs and versions.
