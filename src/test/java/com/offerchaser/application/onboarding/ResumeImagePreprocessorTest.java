package com.offerchaser.application.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResumeImagePreprocessor} 单元测试：图片校验、压缩到长边 1600、JPEG base64 输出。
 * 全部使用内存图片，不联网、不落盘。
 */
class ResumeImagePreprocessorTest {

    private final ResumeImagePreprocessor preprocessor = new ResumeImagePreprocessor();

    @Test
    void rejectsNullFile() {
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> preprocessor.prepare(null));
        assertEquals(OnboardingException.INVALID_IMAGE, error.code());
        assertEquals(400, error.status().value());
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png", new byte[0]);
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> preprocessor.prepare(file));
        assertEquals(OnboardingException.INVALID_IMAGE, error.code());
    }

    @Test
    void rejectsFileLargerThanTenMegabytes() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(ResumeImagePreprocessor.MAX_IMAGE_BYTES + 1);

        OnboardingException error = assertThrows(OnboardingException.class,
                () -> preprocessor.prepare(file));
        assertEquals(OnboardingException.INVALID_IMAGE, error.code());
        assertTrue(error.getMessage().contains("10MB"));
        verify(file, never()).getBytes();
    }

    @Test
    void rejectsNonImageBytes() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png",
                "this is not an image".getBytes());
        OnboardingException error = assertThrows(OnboardingException.class,
                () -> preprocessor.prepare(file));
        assertEquals(OnboardingException.INVALID_IMAGE, error.code());
        assertTrue(error.getMessage().contains("无法识别的图片"));
    }

    @Test
    void compressesLongEdgeTo1600AndOutputsJpegDataUrl() throws Exception {
        byte[] png = pngBytes(2400, 1200);
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png", png);

        ResumeImagePreprocessor.PreparedImage prepared = preprocessor.prepare(file);

        assertEquals(1600, prepared.width());
        assertEquals(800, prepared.height());
        assertTrue(prepared.dataUrl().startsWith("data:image/jpeg;base64,"));
        assertEquals(png.length, prepared.sourceBytes());
        assertTrue(prepared.outputBytes() > 0);

        byte[] jpeg = Base64.getDecoder().decode(prepared.dataUrl().substring("data:image/jpeg;base64,".length()));
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(1600, decoded.getWidth());
        assertEquals(800, decoded.getHeight());
    }

    @Test
    void doesNotUpscaleSmallImage() throws Exception {
        byte[] png = pngBytes(800, 600);
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png", png);

        ResumeImagePreprocessor.PreparedImage prepared = preprocessor.prepare(file);

        assertEquals(800, prepared.width());
        assertEquals(600, prepared.height());
    }

    @Test
    void transparentPngIsConvertedToJpegWithWhiteBackground() throws Exception {
        BufferedImage argb = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = argb.createGraphics();
        graphics.setColor(new Color(0, 0, 0, 0));
        graphics.fillRect(0, 0, 1200, 900);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(100, 100, 400, 300);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(argb, "png", output);
        MockMultipartFile file = new MockMultipartFile("file", "resume.png", "image/png", output.toByteArray());

        ResumeImagePreprocessor.PreparedImage prepared = preprocessor.prepare(file);

        assertEquals(1200, prepared.width());
        assertEquals(900, prepared.height());
        byte[] jpeg = Base64.getDecoder().decode(prepared.dataUrl().substring("data:image/jpeg;base64,".length()));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(1200, decoded.getWidth());
        assertEquals(900, decoded.getHeight());
        // 透明区域应被铺成白色，而不是 JPEG 默认的黑色
        int rgb = decoded.getRGB(10, 10);
        assertTrue(((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) > 200,
                "透明背景应转为接近白色");
    }

    private static byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(10, 10, width / 2, height / 2);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
