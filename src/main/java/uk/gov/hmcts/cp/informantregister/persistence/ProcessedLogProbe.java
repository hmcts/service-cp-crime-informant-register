package uk.gov.hmcts.cp.informantregister.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Can the processed log be reached right now?
 *
 * <p>One question, answered by asking the database the cheapest thing it can answer. It exists
 * because processed-log availability is a <strong>precondition</strong> rather than a step (spec
 * FR-015): a delivery that arrives without a store must be handed back before it is examined, not
 * part-way through being processed, and the consumer lifecycle controller must be able to ask the
 * same question on a schedule to know when the outage is over.
 *
 * <p>The failure is caught and turned into an answer rather than propagated — which is what this
 * class is <em>for</em>, and is not a swallowed exception: every caller acts on the answer, and the
 * two that matter both do something loud with it. The exception itself is reported at DEBUG,
 * because during an outage this runs every few seconds and a stack trace per probe would bury the
 * one ERROR that says intake has stopped.
 */
public class ProcessedLogProbe {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessedLogProbe.class);

    /**
     * The cheapest round trip there is. It is asked of the pool rather than of a table on purpose:
     * the question is "is the store reachable", and a query naming {@code processed_request} would
     * also fail on an unmigrated schema — which is a different problem with a different answer, and
     * one this probe is deliberately asked <em>before</em>.
     */
    private static final String PROBE = "SELECT 1";

    private final JdbcClient jdbcClient;

    public ProcessedLogProbe(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * @return whether the processed log answered
     */
    public boolean available() {
        boolean reachable;
        try {
            jdbcClient.sql(PROBE).query(Integer.class).single();
            reachable = true;
        } catch (DataAccessException unreachable) {
            LOG.debug("The processed log did not answer the probe.", unreachable);
            reachable = false;
        }
        return reachable;
    }
}
