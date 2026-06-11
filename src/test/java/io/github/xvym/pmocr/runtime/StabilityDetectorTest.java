package io.github.xvym.pmocr.runtime;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StabilityDetectorTest {
    @Test
    public void emitsOnlyAfterFingerprintStaysStableForConfiguredWindow() {
        StabilityDetector detector = new StabilityDetector(350L);

        assertFalse(detector.shouldEmit(10L, 1_000L));
        assertFalse(detector.shouldEmit(11L, 1_100L));
        assertFalse(detector.shouldEmit(11L, 1_449L));
        assertTrue(detector.shouldEmit(11L, 1_450L));
    }

    @Test
    public void doesNotEmitSameStableTextTwiceUntilReset() {
        StabilityDetector detector = new StabilityDetector(350L);

        assertFalse(detector.shouldEmit(20L, 2_000L));
        assertTrue(detector.shouldEmit(20L, 2_350L));
        assertTrue(detector.wasEmitted(20L));
        assertFalse(detector.shouldEmit(20L, 3_000L));

        detector.reset();

        assertFalse(detector.shouldEmit(20L, 4_000L));
        assertTrue(detector.shouldEmit(20L, 4_350L));
    }
}
