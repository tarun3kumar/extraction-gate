package gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import gate.FieldCheck.FieldResult;
import gate.FieldCheck.Type;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Release gate: decides whether extractor 1.5.0-rc.1 (release candidate) can replace 1.4.0 (production, the baseline).
 * The expected results are the files in starter-pack/expected (the task also calls them ground truth).
 * 1. fromStarterPack() reads the schema, the expected results and the recorded outputs of both versions.
 * 2. The constructor   validates both records of every patient case against the schema and compares every
 *                      field of both versions with the expected results (FieldCheck.compare).
 * 3. compareWithBaseline() notes the release candidate's regressions and improvements against the baseline.
 * 4. judge()           sorts every wrong field of the release candidate into blockers or acceptable differences.
 * 5. report() and go() give the report text and the verdict, which ExtractionGateTest asserts.
 */
public final class Gate {

    /** Folder with the schema, the expected results and the recorded outputs. */
    private static final Path STARTER_PACK = Path.of("starter-pack");

    /** Reads JSON files. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The extractor_version in the baseline records: 1.4.0, in production. */
    private String baselineName;

    /** The extractor_version in the release-candidate records: 1.5.0-rc.1. */
    private String releaseCandidateName;

    /** Report lines that stop the release; the verdict is GO only when this list is empty. */
    final List<String> blockers = new ArrayList<>();

    /** Report lines for differences that are worth a look but do not stop the release. */
    private final List<String> acceptable = new ArrayList<>();

    /**
     * Regressions and improvements of the release candidate compared with the baseline, and baseline records that
     * break the schema.
     */
    private final List<String> comparison = new ArrayList<>();

    /**
     * Builds the gate on the starter pack: the schema, the expected results and the recorded outputs of both
     * versions.
     */
    static Gate fromStarterPack() throws IOException {
        Path schemaFile = STARTER_PACK.resolve("schema/extraction-record.v1.schema.json");
        // V202012: the JSON Schema version the schema file declares in its "$schema" line.
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of(schemaFile.toUri().toString()));
        return new Gate(schema, readFolder("expected"), readFolder("outputs/v1"), readFolder("outputs/v2"));
    }

    /**
     * Decides on the release candidate, case by case and field by field.
     *
     * @param schema                  the JSON schema every record must match
     * @param expected                the expected results, one record per patient case
     * @param baselineOutputs         recorded outputs of the version in production, 1.4.0 in the starter pack
     * @param releaseCandidateOutputs recorded outputs of the release candidate, 1.5.0-rc.1 in the starter pack
     */
    private Gate(JsonSchema schema, Map<String, JsonNode> expected, Map<String, JsonNode> baselineOutputs,
         Map<String, JsonNode> releaseCandidateOutputs) {
        for (String caseId : expected.keySet()) {
            JsonNode baselineRecord = baselineOutputs.get(caseId);
            JsonNode releaseCandidateRecord = releaseCandidateOutputs.get(caseId);

            // 1. The version names, for the report header.
            baselineName = baselineRecord.path("extractor_version").asText();
            releaseCandidateName = releaseCandidateRecord.path("extractor_version").asText();

            // 2. Validate both records against the schema. Only the release candidate's record can block;
            // an invalid baseline record is only shown, because the baseline is already in production.
            blockers.addAll(validate(schema, caseId, releaseCandidateRecord, "SCHEMA_VIOLATION"));
            comparison.addAll(validate(schema, caseId, baselineRecord, "BASELINE_SCHEMA_VIOLATION"));

            // 3. Compare every field of both versions with the expected results, by the same rules,
            // then judge the release candidate's field next to the baseline's.
            for (Map.Entry<String, JsonNode> expectedEntry : expected.get(caseId).path("fields").properties()) {
                String fieldName = expectedEntry.getKey();
                JsonNode expectedField = expectedEntry.getValue();
                JsonNode baselineField = baselineRecord.path("fields").path(fieldName);
                JsonNode releaseCandidateField = releaseCandidateRecord.path("fields").path(fieldName);
                FieldResult before = FieldCheck.compare(caseId, fieldName, expectedField, baselineField);
                FieldResult now = FieldCheck.compare(caseId, fieldName, expectedField, releaseCandidateField);
                compareWithBaseline(now, before);
                judge(now, before);
            }
        }
    }

    /** The verdict: the release candidate can ship only when nothing blocks it. */
    boolean go() {
        return blockers.isEmpty();
    }

    /** A field that works in production and breaks in the release candidate is a regression, whatever the field. */
    private static boolean isRegression(FieldResult now, FieldResult before) {
        return before.correct() && !now.correct();
    }

    /**
     * Notes a field that the release candidate breaks (regression) or fixes (improvement) compared with the
     * baseline.
     */
    private void compareWithBaseline(FieldResult now, FieldResult before) {
        String change = before.actual() + " -> " + now.actual();
        if (isRegression(now, before)) {
            comparison.add(line(now.caseId(), now.fieldName(), "REGRESSION", change));
        }
        if (!before.correct() && now.correct()) {
            comparison.add(line(now.caseId(), now.fieldName(), "IMPROVEMENT", change));
        }
    }

    /** Turns the release candidate's finding on one field into one blocker or acceptable-difference line. */
    private void judge(FieldResult now, FieldResult before) {
        // Nothing wrong: no line.
        if (now.correct()) {
            return;
        }
        boolean regression = isRegression(now, before);
        String reasons = now.type() + (regression ? ", REGRESSION" : "");
        String line = line(now.caseId(), now.fieldName(), reasons,
                "expected " + now.expected() + ", got " + now.actual() + now.detail());
        if (regression || blocks(now.type(), now.fieldName())) {
            blockers.add(line);
        } else {
            acceptable.add(line);
        }
    }

    /** Whether one finding stops the release on its own. */
    private static boolean blocks(Type type, String fieldName) {
        // Hiding a disagreement between documents removes the cue that a human must check the source.
        if (type == Type.SUPPRESSED_CONFLICT) {
            return true;
        }
        // Inventing, dropping or changing a fact blocks on the fields that drive clinical decisions.
        return !FieldCheck.NOT_CRITICAL.contains(fieldName);
    }

    /** The report text: verdict, blockers, acceptable differences and the comparison with the baseline. */
    String report() {
        List<String> report = new ArrayList<>();
        report.add("EXTRACTION REGRESSION GATE: " + (go() ? "GO" : "NO-GO"));
        report.add("Release candidate " + releaseCandidateName + " vs baseline " + baselineName + " (production)");
        section(report, "BLOCKERS", blockers);
        section(report, "ACCEPTABLE DIFFERENCES", acceptable);
        section(report, "COMPARED WITH BASELINE " + baselineName, comparison);
        return String.join("\n", report) + "\n";
    }

    /** Adds one report section: a blank line, the title with its number of lines, then the lines. */
    private static void section(List<String> report, String title, List<String> lines) {
        report.add("");
        report.add(title + " (" + lines.size() + ")");
        report.addAll(lines);
    }

    /** One report line in fixed columns: case, field name, what was found, detail. */
    private static String line(String caseId, String fieldName, String what, String detail) {
        return String.format("  %-9s %-20s %-35s %s", caseId, fieldName, what, detail);
    }

    /**
     * One line for a record that breaks the schema, empty when it is valid.
     * The text in 'what' labels the line: SCHEMA_VIOLATION for the release candidate, BASELINE_SCHEMA_VIOLATION for
     * the baseline.
     */
    private static List<String> validate(JsonSchema schema, String caseId, JsonNode record, String what) {
        Set<ValidationMessage> messages = schema.validate(record);
        if (messages.isEmpty()) {
            return List.of();
        }

        // One wrong value gives several messages. Each message has a path to the place it complains about:
        //   $.fields.lymph_nodes                  2 steps: something in this field is wrong
        //   $.fields.lymph_nodes.value.positive   4 steps: this exact value is wrong
        // The message with the longest path is the most specific one, so the report shows that one.
        ValidationMessage mostSpecific = null;
        int longestPath = -1;
        for (ValidationMessage message : messages) {
            int pathLength = message.getInstanceLocation().getNameCount();
            if (pathLength > longestPath) {
                mostSpecific = message;
                longestPath = pathLength;
            }
        }
        return List.of(line(caseId, "(record)", what, mostSpecific.getMessage()));
    }

    /**
     * Reads every JSON file of a starter-pack folder, keyed by case name (patient1, ...).
     * A TreeMap keeps the cases sorted by name, so the report lists them in the same order on every machine.
     */
    private static Map<String, JsonNode> readFolder(String folder) throws IOException {
        File[] files = STARTER_PACK.resolve(folder).toFile().listFiles();
        Map<String, JsonNode> records = new TreeMap<>();
        for (File file : files) {
            if (file.getName().endsWith(".json")) { // skips files such as .DS_Store
                records.put(file.getName().replace(".json", ""), JSON.readTree(file));
            }
        }
        return records;
    }
}
