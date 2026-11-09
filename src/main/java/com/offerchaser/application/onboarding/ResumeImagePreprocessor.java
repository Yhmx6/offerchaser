package com.offerchaser.application.onboarding;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * 简历图片预处理：校验（非空/≤10MB/真实图片）→ 等比压缩到长边 ≤1600 → JPEG → base64 data URL。
 * <p>
 * 安全约定：
 * <ul>
 *   <li>全程在内存中处理，不创建临时文件、不落盘、不保存图片；</li>
 *   <li>拒绝空文件、超大文件、非图片内容与像素过多的图片（防解压炸弹）；</li>
 *   <li>不打印图片二进制/简历内容，错误消息只说明原因。</li>
 * </ul>
 * </p>
 */
@Component
public class ResumeImagePreprocessor {

    /** 单张简历图片上限：10MB（与前端/上传容器限制一致） */
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    /** 压缩后长边上限（像素） */
    public static final int MAX_LONG_EDGE = 1600;
    /** 原图像素上限，防止小文件解压出超大位图 */
    static final long MAX_SOURCE_PIXELS = 40_000_000L;
    /** JPEG 质量：兼顾文字清晰度与请求体大小 */
    private static final float JPEG_QUALITY = 0.85f;

    /** 预处理结果：data URL + 压缩后的尺寸/体积（只用于日志与测试，不落库）。 */
    public record PreparedImage(String dataUrl, int width, int height, long sourceBytes, long outputBytes) {
    }

    /**
     * 校验并压缩简历图片。
     *
     * @param file multipart 上传的图片
     * @return 可直接用于 chat-completions image_url 的 data URL 与尺寸信息
     * @throws OnboardingException 图片为空/超过 10MB/不是真实图片/尺寸异常（INVALID_IMAGE，400）
     */
    public PreparedImage prepare(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw OnboardingException.invalidImage("请上传简历图片（PNG/JPG/WebP）");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw OnboardingException.invalidImage("图片不能超过 10MB，请压缩后重试");
        }

        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw OnboardingException.invalidImage("读取简历图片失败，请重试");
        }
        if (raw == null || raw.length == 0) {
            throw OnboardingException.invalidImage("简历图片为空，请重新上传");
        }
        if (raw.length > MAX_IMAGE_BYTES) {
            throw OnboardingException.invalidImage("图片不能超过 10MB，请压缩后重试");
        }

        BufferedImage source = readImage(raw);
        long pixels = (long) source.getWidth() * source.getHeight();
        if (pixels > MAX_SOURCE_PIXELS) {
            throw OnboardingException.invalidImage("图片像素过大，请压缩后重试");
        }

        BufferedImage scaled = scaleToLongEdge(source);
        byte[] jpeg = writeJpeg(scaled);
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
        return new PreparedImage(dataUrl, scaled.getWidth(), scaled.getHeight(), raw.length, jpeg.length);
    }

    /** 读取图片；无法识别为真实图片时返回明确错误。 */
    private BufferedImage readImage(byte[] raw) {
        BufferedImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(raw)) {
            image = ImageIO.read(input);
        } catch (IOException e) {
            throw OnboardingException.invalidImage("无法识别的图片文件，请上传 PNG/JPG/WebP 截图");
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw OnboardingException.invalidImage("无法识别的图片文件，请上传 PNG/JPG/WebP 截图");
        }
        return image;
    }

    /** 等比缩放到长边 ≤1600；已经足够小的图片不放大。 */
    private BufferedImage scaleToLongEdge(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longestEdge = Math.max(width, height);
        double scale = longestEdge <= MAX_LONG_EDGE ? 1.0 : (double) MAX_LONG_EDGE / longestEdge;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        // JPEG 不支持透明通道：统一铺白底再画，避免 PNG 透明区域变成黑块
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** 编码为 JPEG（固定质量 0.85）。 */
    private byte[] writeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("当前 JVM 不支持 JPEG 编码");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("压缩简历图片失败: " + e.getMessage(), e);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}
