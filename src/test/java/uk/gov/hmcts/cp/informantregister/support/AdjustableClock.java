package uk.gov.hmcts.cp.informantregister.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock a test moves by hand.
 *
 * <p>For rules whose whole content is "how long ago was that?". Waiting real seconds to observe one
 * would trade a fast, exact assertion for a slow, approximate one, and it would make the boundary —
 * the difference between just inside the window and just outside it — untestable: the two cases are
 * milliseconds apart, and a sleeping test cannot land on either of them deliberately.
 */
public final class AdjustableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    private AdjustableClock(final Instant start, final ZoneId zone) {
        this.now = new AtomicReference<>(start);
        this.zone = zone;
    }

    /**
     * A clock reading the given instant until a test moves it.
     */
    public static AdjustableClock startingAt(final Instant start) {
        return new AdjustableClock(start, ZoneOffset.UTC);
    }

    /**
     * Moves the clock forward.
     *
     * @param amount how far forward
     */
    public void advance(final Duration amount) {
        now.updateAndGet(current -> current.plus(amount));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(final ZoneId otherZone) {
        return new AdjustableClock(now.get(), otherZone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
