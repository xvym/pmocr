package io.github.xvym.pmocr;

/**
 * @Author: Xv
 * @Date: 2026/6/9
 * @Description: 根据文字区域指纹判断对话文本是否已经加载稳定。
 */
final class StabilityDetector {
    private final long stableMillis;
    private long candidateFingerprint;
    private long candidateSince;
    private long emittedFingerprint = Long.MIN_VALUE;
    private boolean hasCandidate;

    StabilityDetector(long stableMillis) {
        this.stableMillis = stableMillis;
    }

    /**
     * 当同一个指纹持续超过稳定时间且尚未输出过时，返回 true。
     */
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

    /**
     * 判断当前指纹是否已经触发过识别输出。
     */
    boolean wasEmitted(long fingerprint) {
        return fingerprint == emittedFingerprint;
    }

    /**
     * 清空候选指纹和已输出指纹，通常在文本框消失或识别停止时调用。
     */
    void reset() {
        hasCandidate = false;
        emittedFingerprint = Long.MIN_VALUE;
    }
}
