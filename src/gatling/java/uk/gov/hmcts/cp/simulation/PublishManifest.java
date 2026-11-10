package uk.gov.hmcts.cp.simulation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append-only CSV record of every message the simulations successfully published, read by
 * {@code src/gatling/scripts/verify-outputs.sh} to correlate Results POSTs back to requests and to
 * measure publish-to-POST latency.
 *
 * <p>Gatling 3.11+ writes {@code simulation.log} in a binary format, so the publish count and
 * timings cannot be recovered from it; this file is the verifier's source of truth for what was
 * sent and when.
 *
 * <p>Columns: {@code simulation,scenario,requestId,hearingId,publishedAtEpochMs}. One row per
 * publish, so a duplicate appears twice with the same requestId and hearingId. Both simulations
 * append to the same file because they share one WireMock journal.
 *
 * <p>Path: system property {@code gatling.manifestPath}, default
 * {@code build/nft/published-manifest.csv}.
 */
public final class PublishManifest {

    static final String HEADER = "simulation,scenario,requestId,hearingId,publishedAtEpochMs";

    private static final Path PATH = Path.of(
            System.getProperty("gatling.manifestPath", "build/nft/published-manifest.csv"));

    private static BufferedWriter writer;

    private PublishManifest() {
    }

    /**
     * Records one successful publish. Thread-safe; Gatling calls this from many virtual users.
     *
     * @param simulation  the simulation class's simple name
     * @param scenario    the Gatling scenario name, so warm-up can be excluded from latency gates
     * @param message     the published message
     * @param publishedAt epoch millis taken immediately before the send
     */
    public static synchronized void record(final String simulation, final String scenario,
                                           final PublishedMessage message, final long publishedAt) {
        try {
            writer().write(String.join(",", simulation, scenario,
                    message.requestId().toString(), message.hearingId().toString(),
                    Long.toString(publishedAt)));
            writer().newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write publish manifest " + PATH, e);
        }
    }

    /**
     * Flushes and closes the manifest. Called from each simulation's {@code after()}; a later
     * simulation in the same JVM reopens it in append mode.
     */
    public static synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close publish manifest " + PATH, e);
        } finally {
            writer = null;
        }
    }

    public static Path path() {
        return PATH;
    }

    private static BufferedWriter writer() throws IOException {
        if (writer == null) {
            final Path parent = PATH.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            final boolean fresh = Files.notExists(PATH) || Files.size(PATH) == 0;
            writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                writer.write(HEADER);
                writer.newLine();
            }
        }
        return writer;
    }
}
