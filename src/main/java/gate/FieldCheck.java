package gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/** Compares one field of an extractor output with the expected results: first the status, then the values. */
final class FieldCheck {

    /** Free-text fields: compared by shared words, because wording varies between runs. */
    private static final Set<String> FREE_TEXT =
            Set.of("primary_diagnosis", "adjuvant_therapy", "surgery_procedure", "secondary_diagnoses");

    /**
     * A defect on this field does not stop the release if 1.4.0 in production has the same defect.
     * If 1.4.0 got the field right, the release candidate made it worse, and the defect blocks.
     */
    static final Set<String> NOT_CRITICAL = Set.of("secondary_diagnoses");

    /**
     * Two free texts are the same statement when at least 60% of the shorter text's content words appear in the other.
     * Example: "Sigmoid colon adenocarcinoma" and "Adenocarcinoma of the sigmoid colon" match: without "of" and "the",
     * both have the same three words.
     * 60% is a starting value, not a validated one; it should be calibrated on text pairs a clinician has labeled.
     */
    private static final double TEXT_MATCH_MIN = 0.6;

    /** Turns a JSON object into a map sorted by property name. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Words that carry no clinical meaning, left out before the words are counted. */
    private static final Set<String> STOPWORDS = Set.of("of", "the", "with", "and", "a", "an", "in", "to", "for");

    /** What the comparison of one field can find. */
    enum Type { CORRECT, HALLUCINATION, MISSED, SUPPRESSED_CONFLICT, FALSE_CONFLICT, WRONG_VALUE }

    /**
     * The outcome for one field of one case.
     *
     * @param caseId    the patient case, for example patient1
     * @param fieldName the field name, for example mmr_status
     * @param expected  short text of the expected value, for the report
     * @param actual    short text of the output value, for the report
     * @param type      what the comparison found
     * @param detail    for a list with a wrong value, the missing items; otherwise empty
     */
    record FieldResult(String caseId, String fieldName, String expected, String actual, Type type, String detail) {
        boolean correct() {
            return type == Type.CORRECT;
        }
    }

    /**
     * Compares one output field with the expected results. The status is checked first, because a wrong status is the
     * worst error: a hallucinated value, a missed value, a suppressed conflict or a false conflict. When the status
     * is the same, the field is wrong if an expected value is missing in the output.
     */
    static FieldResult compare(String caseId, String fieldName, JsonNode expectedField, JsonNode actualField) {
        // A field that is missing in the output counts as unknown.
        String expectedStatus = expectedField.path("status").asText("unknown");
        String actualStatus = actualField.path("status").asText("unknown");

        // The expected values that the output does not have. Example: expected allergies [Penicillin, Latex],
        // output [Penicillin, Aspirin]: missingFromOutput = [Latex], the output dropped it.
        // TODO: also check the other direction, the values the output adds (Aspirin in the example). It is left
        // out to keep this exercise short, so an invented item in a list is not detected today.
        List<String> missingFromOutput = unmatched(fieldName, values(expectedField), values(actualField));

        // The first four branches: the status differs. The fifth: the same status, but an expected value is missing.
        Type type = Type.CORRECT;
        if (expectedStatus.equals("unknown") && !actualStatus.equals("unknown")) {
            type = Type.HALLUCINATION;
        } else if (!expectedStatus.equals("unknown") && actualStatus.equals("unknown")) {
            type = Type.MISSED;
        } else if (expectedStatus.equals("conflict") && actualStatus.equals("known")) {
            type = Type.SUPPRESSED_CONFLICT;
        } else if (expectedStatus.equals("known") && actualStatus.equals("conflict")) {
            type = Type.FALSE_CONFLICT;
        } else if (!missingFromOutput.isEmpty()) {
            type = Type.WRONG_VALUE;
        }

        // For a list with a wrong value, the report names the missing items.
        String detail = "";
        if (type == Type.WRONG_VALUE && expectedField.path("value").isArray()) {
            detail = " (missing " + missingFromOutput + ")";
        }
        return new FieldResult(caseId, fieldName, describe(expectedField), describe(actualField), type, detail);
    }

    /** The values that have no matching value in otherValues; compare() uses it for the expected values. */
    private static List<String> unmatched(String fieldName, List<String> values, List<String> otherValues) {
        List<String> unmatched = new ArrayList<>();
        for (String value : values) {
            // Look for this value on the other side.
            boolean found = false;
            for (String otherValue : otherValues) {
                if (matches(fieldName, value, otherValue)) {
                    found = true;
                }
            }
            // Not found on the other side: the value is unmatched.
            if (!found) {
                unmatched.add(value);
            }
        }
        return unmatched;
    }

    /** Two values match when the text is exactly the same; free text matches when enough of its words are shared. */
    private static boolean matches(String fieldName, String first, String second) {
        if (!FREE_TEXT.contains(fieldName)) {
            return first.equals(second);
        }
        Set<String> shared = words(first);
        shared.retainAll(words(second));
        int shorter = Math.min(words(first).size(), words(second).size());
        // Match when at least one word is shared and
        // the shared words are at least 60% of the shorter text
        return !shared.isEmpty() && shared.size() >= TEXT_MATCH_MIN * shorter;
    }

    /** The lower-case words of a text without the stopwords. */
    private static Set<String> words(String text) {
        // Split on every character that is not part of a word; (?U) makes this work for any language.
        String[] parts = text.toLowerCase(Locale.ROOT).split("(?U)\\W+");
        Set<String> words = new HashSet<>(Arrays.asList(parts));
        words.remove(""); // split gives an empty first word when the text starts with a non-word character, e.g. "{" of an object
        words.removeAll(STOPWORDS);
        return words;
    }

    /** The values of a field as text: the conflict candidates, the items of a list, or the single value. */
    private static List<String> values(JsonNode field) {
        List<String> values = new ArrayList<>();
        for (JsonNode conflictCandidate : field.path("candidates")) {
            values.add(text(conflictCandidate.path("value")));
        }
        if (field.path("value").isArray()) {
            for (JsonNode item : field.get("value")) {
                values.add(text(item));
            }
        } else if (field.has("value")) {
            values.add(text(field.get("value")));
        }
        return values;
    }

    /**
     * A value as text. An object, such as an allergy or the lymph-node counts, becomes its properties in name
     * order, so that neither the order nor the quotes matter: "3" equals 3; the schema check reports the type error.
     */
    private static String text(JsonNode value) {
        return value.isObject() ? JSON.convertValue(value, TreeMap.class).toString() : value.asText();
    }

    /** Short text for the report: the value, the number of list items, or the conflict candidates. */
    private static String describe(JsonNode field) {
        String status = field.path("status").asText("unknown");
        if (status.equals("unknown")) {
            return "unknown";
        }
        if (field.path("value").isArray()) {
            return field.get("value").size() + " items";
        }
        // Delimiter is used for display report like 1967-03-15 -> conflict {1967-03-15 / 1976-03-15}
        String values = String.join(" / ", values(field));
        return status.equals("conflict") ? "conflict {" + values + "}" : values;
    }
}
