package io.github.xvym.pmocr;

import io.github.xvym.pmocr.ui.MainWindow;

import javax.swing.*;

/**
 * @Author: Xv
 * @Date: 2026/6/9 21:34
 * @Description: 宝可梦OCR翻译应用
 */
public final class PmOcrApp {
    private PmOcrApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }

}
