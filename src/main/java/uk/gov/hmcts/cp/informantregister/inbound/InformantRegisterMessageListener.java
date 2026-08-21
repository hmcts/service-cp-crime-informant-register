package uk.gov.hmcts.cp.informantregister.inbound;

import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;

/**
 * Seam for T026. The behaviour is specified by {@code MessageListenerSettlementTest}.
 */
public class InformantRegisterMessageListener {

    private final DistributionCommandParser parser;
    private final DistributionPipeline pipeline;
    private final ProcessingMetrics metrics;

    public InformantRegisterMessageListener(
            final DistributionCommandParser parser,
            final DistributionPipeline pipeline,
            final ProcessingMetrics metrics) {
        this.parser = parser;
        this.pipeline = pipeline;
        this.metrics = metrics;
    }

    /**
     * Handles one delivery, from receipt to its single settlement.
     *
     * @param context the delivery, and the settlement calls it permits
     */
    public void onMessage(final ServiceBusReceivedMessageContext context) {
        throw new UnsupportedOperationException("every delivery is settled exactly once");
    }
}
