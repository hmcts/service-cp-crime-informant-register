package uk.gov.hmcts.cp.logging;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class JunitLoggingTest {

    @Test
    void junit_should_log_correct_fields() throws JacksonException {
        MDC.put("traceId", "1234-1234");
        final ByteArrayOutputStream capturedStdOut = captureStdOut();
        log.info("junit test message");

        final Map<String, Object> capturedFields = new ObjectMapper().readValue(capturedStdOut.toString(StandardCharsets.UTF_8), new TypeReference<>() {
        });

        assertThat(capturedFields)
                .containsEntry("traceId", "1234-1234")
                .containsKey("timestamp") // or keep .get("timestamp").isNotNull()
                .containsEntry("logger_name", "uk.gov.hmcts.cp.logging.JunitLoggingTest")
                .containsEntry("thread_name", "Test worker")
                .containsEntry("level", "INFO")
                .containsEntry("message", "junit test message");
    }

    private ByteArrayOutputStream captureStdOut() {
        final ByteArrayOutputStream capturedStdOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedStdOut, true, StandardCharsets.UTF_8));
        return capturedStdOut;
    }
}
