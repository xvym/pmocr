package io.github.xvym.pmocr.ocr;

import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DialogBoxDetectorTest {
    @Test
    public void detectsStandardDialogBoxFromBorderLines() {
        BufferedImage image = new BufferedImage(240, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.fillRect(20, 20, 151, 1);
            graphics.fillRect(20, 62, 151, 1);
        } finally {
            graphics.dispose();
        }

        TextBox box = new DialogBoxDetector().detect(image);

        assertNotNull(box);
        assertEquals(1.0, box.scale, 0.0001);
        assertEquals(24.0, box.textX, 0.0001);
        assertEquals(34.0, box.firstLineY, 0.0001);
        assertEquals(new Rectangle(18, 20, 155, 43), box.bounds);
    }

    @Test
    public void ignoresImagesWithoutDialogBoxBorders() {
        BufferedImage image = new BufferedImage(240, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }

        assertNull(new DialogBoxDetector().detect(image));
    }
}
