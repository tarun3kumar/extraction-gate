package gate;

import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * The release gate itself: test T-01 from the QA plan.
 * It compares the recorded outputs of extractor 1.4.0 (production) and 1.5.0-rc.1 (release candidate) in
 * starter-pack/ with the expected results. The test passes when the release candidate may ship (GO) and fails
 * when it must not (NO-GO). For 1.5.0-rc.1 it is expected to fail.
 * Run it with: ./gradlew test
 */
public class ExtractionGateTest {

    /** Where the report is written, in addition to the console. */
    private static final Path REPORT_FILE = Path.of("build/gate-report.txt");

    // T-01: the release candidate may ship only when the gate finds no blocker.
    @Test
    public void releaseCandidateMayShip() throws IOException {
        Gate gate = Gate.fromStarterPack();
        String report = gate.report();
        System.out.print(report);
        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(REPORT_FILE, report);
        assertTrue(gate.go(), "NO-GO: " + gate.blockers.size() + " blocker(s); full report in " + REPORT_FILE);
    }
}
