package io.github.xvym.pmocr;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class PmOcrApp {
    private PmOcrApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if ("--verify".equals(args[0])) {
                File directory = new File(args.length > 1 ? args[1] : "testphoto");
                System.exit(verify(directory) ? 0 : 1);
            }
            if ("--image".equals(args[0]) && args.length > 1) {
                recognizeImage(new File(args[1]));
                return;
            }
            if ("--translate".equals(args[0]) && args.length > 1) {
                translateText(joinArgs(args, 1));
                return;
            }
            if ("--help".equals(args[0]) || "-h".equals(args[0])) {
                printHelp();
                return;
            }
            printHelp();
            System.exit(2);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有图形界面，请使用 --image 或 --verify。");
            System.exit(2);
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new MainWindow().setVisible(true);
            }
        });
    }

    private static void recognizeImage(File file) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("不支持的图片: " + file);
        }
        PokemonOcr.Recognition result = new PokemonOcr().recognize(image);
        if (!result.isTextBoxFound()) {
            System.out.println("未检测到对话框");
            return;
        }
        System.out.println(result.getText());
        System.out.println("翻译:");
        System.out.println(XlsxTranslationRepository.loadDefault().translate(result.getText()));
        System.out.printf("倍率=%.2f, 平均误差=%.2f, 未知格=%d%n",
                result.getScale(), result.getAverageDistance(), result.getUnknownCharacters());
    }

    private static void translateText(String text) {
        XlsxTranslationRepository translations = XlsxTranslationRepository.loadDefault();
        System.out.println(translations.translate(text.replace("\\n", "\n")));
        System.out.printf("文本库=%s, 条目=%d, 模板=%d, 名词=%d%n",
                translations.source(), translations.size(), translations.templateSize(), translations.nounSize());
    }

    private static boolean verify(File directory) throws IOException {
        File[] images = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".bmp")
                || name.toLowerCase().endsWith(".png"));
        if (images == null || images.length == 0) {
            throw new IOException("目录中没有测试图片: " + directory.getAbsolutePath());
        }
        Arrays.sort(images, Comparator.comparing(File::getName));
        PokemonOcr ocr = new PokemonOcr();
        int passed = 0;
        long totalNanos = 0L;
        List<String> failures = new ArrayList<String>();
        for (File imageFile : images) {
            File expectedFile = new File(imageFile.getParentFile(), stripExtension(imageFile.getName()) + ".txt");
            if (!expectedFile.isFile()) {
                continue;
            }
            BufferedImage image = ImageIO.read(imageFile);
            long started = System.nanoTime();
            PokemonOcr.Recognition result = ocr.recognize(image);
            long elapsed = System.nanoTime() - started;
            totalNanos += elapsed;
            String expected = new String(Files.readAllBytes(expectedFile.toPath()), StandardCharsets.UTF_8);
            String actualNormalized = normalize(result.getText());
            String expectedNormalized = normalize(expected);
            boolean pass = result.isTextBoxFound() && expectedNormalized.equals(actualNormalized);
            if (pass) {
                passed++;
            } else {
                failures.add(imageFile.getName() + "\n  expected: " + expectedNormalized.replace("\n", " / ")
                        + "\n  actual:   " + actualNormalized.replace("\n", " / "));
            }
            System.out.printf("%s %s  scale=%.2f distance=%.2f unknown=%d time=%.2fms%n",
                    pass ? "PASS" : "FAIL", imageFile.getName(), result.getScale(),
                    result.getAverageDistance(), result.getUnknownCharacters(), elapsed / 1_000_000.0);
        }
        for (String failure : failures) {
            System.out.println(failure);
        }
        int total = passed + failures.size();
        System.out.printf("结果: %d/%d 通过，平均 %.2fms/图%n", passed, total,
                total == 0 ? 0.0 : totalNanos / 1_000_000.0 / total);
        return failures.isEmpty();
    }

    private static String normalize(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u3000', ' ').split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(trimRight(line));
        }
        return trimRight(result.toString());
    }

    private static String trimRight(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(args[i]);
        }
        return result.toString();
    }

    private static void printHelp() {
        System.out.println("用法:");
        System.out.println("  java -jar target/pmocr-1.0.0.jar");
        System.out.println("  java -jar target/pmocr-1.0.0.jar --image testphoto/1.bmp");
        System.out.println("  java -jar target/pmocr-1.0.0.jar --verify testphoto");
        System.out.println("  java -jar target/pmocr-1.0.0.jar --translate \"<PLAYER>は\\nきのみを　もらった！\"");
    }
}
