package io.github.xvym.pmocr.ocr;

import io.github.xvym.pmocr.translation.TranslationIndex;
import io.github.xvym.pmocr.translation.TranslationTextUtils;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class PokemonOcrTextRecognitionTest {

    @Test
    public void recognizesTextFromBundledSamples() throws IOException {
        PokemonOcr ocr = new PokemonOcr();
        for (int i = 1; i <= 4; i++) {
            BufferedImage image = readImage("testpic/" + i + ".bmp");
            String expected = normalize(new String(readResource("testpic/" + i + ".txt"), StandardCharsets.UTF_8));

            PokemonOcr.Recognition recognition = ocr.recognize(image);

            assertTrue("sample " + i + " should contain a dialog box", recognition.isTextBoxFound());
            assertEquals("sample " + i, expected, normalize(recognition.getText()));
            assertEquals("sample " + i + " should not contain unknown characters",
                    0, recognition.getUnknownCharacters());
        }
    }

    private static BufferedImage readImage(String name) throws IOException {
        URL resource = PokemonOcrTextRecognitionTest.class.getClassLoader().getResource(name);
        assertNotNull("missing resource " + name, resource);
        BufferedImage image = ImageIO.read(resource);
        assertNotNull("unsupported image " + name, image);
        return image;
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream input = PokemonOcrTextRecognitionTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("missing resource " + name, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
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
}
