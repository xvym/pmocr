package io.github.xvym.pmocr.ocr;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

final class TextBox {
    final Rectangle bounds;
    final double scale;
    final double textX;
    final double firstLineY;

    TextBox(Rectangle bounds, double scale, double textX, double firstLineY) {
        this.bounds = bounds;
        this.scale = scale;
        this.textX = textX;
        this.firstLineY = firstLineY;
    }

    boolean hasRoom(BufferedImage image) {
        return textX >= 0 && firstLineY - OcrLayout.MARK_OFFSET * scale >= 0
                && textX + OcrLayout.COLUMNS * OcrLayout.TILE_SIZE * scale <= image.getWidth() + scale
                && firstLineY + 24.0 * scale <= image.getHeight() + scale;
    }
}
