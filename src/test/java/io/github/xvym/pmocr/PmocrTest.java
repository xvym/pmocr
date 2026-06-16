package io.github.xvym.pmocr;

import io.github.xvym.pmocr.ocr.PokemonOcr;
import io.github.xvym.pmocr.ocr.PokemonOcrTextRecognitionTest;
import io.github.xvym.pmocr.translation.XlsxTranslationRepository;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import static io.github.xvym.pmocr.translation.TranslationTextUtils.normalize;
import static org.junit.Assert.assertNotNull;

/**
 * @Author: Xv
 * @Date: 2026/6/13 20:55
 * @Description
 */
public class PmocrTest {


    @Test
    public void recognize() throws IOException {
        BufferedImage image = readImage("badcase/0011.bmp");
        PokemonOcr ocr = new PokemonOcr();
        PokemonOcr.Recognition recognition = ocr.recognize(image);
        String text = normalize(recognition.getText());
        XlsxTranslationRepository translations = XlsxTranslationRepository.loadDefault();
        String value = translations.translate(text);
        System.out.println(value);
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

}
