package io.github.xvym.pmocr.runtime;

import io.github.xvym.pmocr.ocr.PokemonOcr;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @Author: Xv
 * @Date: 2026/6/9
 * @Description: 后台实时截图识别循环，负责稳定性检测和识别结果回调。
 */
public final class RealtimeRecognizer {
    /**
     * 实时识别事件监听器，UI 层通过它接收状态和识别文本。
     */
    public interface Listener {
        void onStatus(String status);

        void onText(PokemonOcr.Recognition recognition);
    }

    private final PokemonOcr ocr;
    private final Listener listener;
    private final StabilityDetector stability = new StabilityDetector(350L);
    private ScheduledExecutorService executor;
    private String previousStatus = "";

    public RealtimeRecognizer(PokemonOcr ocr, Listener listener) {
        this.ocr = ocr;
        this.listener = listener;
    }

    /**
     * 启动固定频率截图任务；文字区域稳定后才触发完整 OCR。
     *
     * @param captureArea 用户圈选的屏幕区域
     */
    public synchronized void start(final Rectangle captureArea) throws AWTException {
        stop();
        final Robot robot = new Robot();
        stability.reset();
        previousStatus = "";
        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "pmocr-realtime");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedImage image = robot.createScreenCapture(captureArea);
                    PokemonOcr.Probe probe = ocr.probe(image);
                    if (!probe.isTextBoxFound()) {
                        status("未检测到对话框");
                        stability.reset();
                        return;
                    }
                    if (stability.shouldEmit(probe.getFingerprint(), System.currentTimeMillis())) {
                        PokemonOcr.Recognition recognition = ocr.recognize(image);
                        listener.onText(recognition);
                        status(String.format("识别完成，倍率 %.2f，平均误差 %.2f",
                                recognition.getScale(), recognition.getAverageDistance()));
                    } else if (stability.wasEmitted(probe.getFingerprint())) {
                        status("文字稳定，等待下一段对话");
                    } else {
                        status("已检测到对话框，等待文字加载完成");
                    }
                } catch (RuntimeException e) {
                    status("实时识别失败: " + e.getMessage());
                }
            }
        }, 0L, 80L, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止后台截图任务并清空稳定性状态。
     */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        stability.reset();
    }

    /**
     * 判断实时识别线程是否仍在运行。
     */
    public synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    /**
     * 只在状态文字发生变化时通知监听器，减少 UI 重复刷新。
     */
    private void status(String value) {
        if (!value.equals(previousStatus)) {
            previousStatus = value;
            listener.onStatus(value);
        }
    }
}
