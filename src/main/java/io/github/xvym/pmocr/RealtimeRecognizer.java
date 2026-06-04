package io.github.xvym.pmocr;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class RealtimeRecognizer {
    interface Listener {
        void onStatus(String status);

        void onText(PokemonOcr.Recognition recognition);
    }

    private final PokemonOcr ocr;
    private final Listener listener;
    private final StabilityDetector stability = new StabilityDetector(350L);
    private ScheduledExecutorService executor;
    private String previousStatus = "";

    RealtimeRecognizer(PokemonOcr ocr, Listener listener) {
        this.ocr = ocr;
        this.listener = listener;
    }

    synchronized void start(final Rectangle captureArea) throws AWTException {
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

    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        stability.reset();
    }

    synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    private void status(String value) {
        if (!value.equals(previousStatus)) {
            previousStatus = value;
            listener.onStatus(value);
        }
    }
}
