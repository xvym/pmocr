package io.github.xvym.pmocr;

final class StabilityDetector {
    private final long stableMillis;
    private long candidateFingerprint;
    private long candidateSince;
    private long emittedFingerprint = Long.MIN_VALUE;
    private boolean hasCandidate;

    StabilityDetector(long stableMillis) {
        this.stableMillis = stableMillis;
    }

    boolean shouldEmit(long fingerprint, long now) {
        if (!hasCandidate || fingerprint != candidateFingerprint) {
            candidateFingerprint = fingerprint;
            candidateSince = now;
            hasCandidate = true;
            return false;
        }
        if (fingerprint == emittedFingerprint || now - candidateSince < stableMillis) {
            return false;
        }
        emittedFingerprint = fingerprint;
        return true;
    }

    boolean wasEmitted(long fingerprint) {
        return fingerprint == emittedFingerprint;
    }

    void reset() {
        hasCandidate = false;
        emittedFingerprint = Long.MIN_VALUE;
    }
}
