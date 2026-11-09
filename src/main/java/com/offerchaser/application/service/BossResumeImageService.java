package com.offerchaser.application.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 图片简历存取与预览。文件保存在运行目录的 {@code data/boss/resume} 下，
 * 不进 classpath、不打进 JAR；对外只暴露「当前生效的那一张」。
 *
 * <p>写入流程统一为：先落到同目录临时文件 → 校验大小与真实图片格式 → 原子替换目标文件；
 * 任何一步失败都不会把半截文件当成新简历。当前简历固定命名为 {@code resume.png|jpg|gif}，
 * 切换格式时会清理其余扩展名的旧文件，避免同一时刻存在多张“当前简历”。</p>
 */
@Service
public class BossResumeImageService {

    /** 单张图片简历的大小上限（10MB）。 */
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    /** 管理页面预览当前图片简历的地址。 */
    public static final String PREVIEW_URL = "/api/boss/config/resume-image/content";

    /** 当前简历允许的扩展名；列表顺序即查找优先级（png > jpg > gif）。 */
    private static final List<String> CURRENT_FORMATS = List.of("png", "jpg", "gif");
    /** 当前简历的固定文件名（不含扩展名）。 */
    private static final String CURRENT_BASENAME = "resume";
    private static final String TEMP_PREFIX_UPLOAD = "resume-upload-";
    private static final String TEMP_PREFIX_TEMPLATE = "resume-from-template-";
    private static final String TEMP_PREFIX_DIRECTORY = "template-resume-";
    private static final String TEMP_SUFFIX = ".tmp";

    private static final String SIZE_ERROR = "图片大小不能超过 10MB";
    private static final String EMPTY_UPLOAD_ERROR = "请选择要上传的图片简历";
    private static final String UNREADABLE_TEMPLATE_ERROR = "模板简历文件不存在或不可读";

    private final Path storageDirectory = Paths.get(System.getProperty("user.dir"), "data", "boss", "resume")
            .toAbsolutePath()
            .normalize();

    /**
     * 保存用户上传的图片简历，替换当前生效的那一张。
     *
     * @param file 管理页面上传的图片文件
     * @return 保存后的文件信息
     * @throws IOException 磁盘写入失败
     */
    public synchronized ResumeImageInfo save(MultipartFile file) throws IOException {
        requireUpload(file);
        Files.createDirectories(storageDirectory);
        Path staged = stageUpload(file, storageDirectory, TEMP_PREFIX_UPLOAD);
        try {
            return promoteToCurrentImage(staged);
        } finally {
            deleteQuietly(staged);
        }
    }

    /**
     * 把一份已有的图片文件复制为当前简历（方向模板激活时切模板简历使用），源文件保留。
     *
     * @param source 模板目录里的图片文件
     * @return 保存后的文件信息
     * @throws IOException 磁盘写入失败
     */
    public synchronized ResumeImageInfo saveFromPath(Path source) throws IOException {
        requireTemplateSource(source);
        Files.createDirectories(storageDirectory);
        Path staged = copyToStagingFile(source, storageDirectory, TEMP_PREFIX_TEMPLATE);
        try {
            return promoteToCurrentImage(staged);
        } finally {
            deleteQuietly(staged);
        }
    }

    /**
     * 把上传的图片保存到指定目录（方向模板专用），不影响当前生效的简历。
     *
     * @param file     上传的图片
     * @param directory 目标目录
     * @param baseName 目标文件名（不含扩展名）
     * @return 保存后的绝对路径
     * @throws IOException 磁盘写入失败
     */
    public synchronized Path saveToDirectory(MultipartFile file, Path directory, String baseName) throws IOException {
        requireUpload(file);
        Files.createDirectories(directory);
        Path staged = stageUpload(file, directory, TEMP_PREFIX_DIRECTORY);
        try {
            String extension = detectExtension(staged);
            Path target = directory.resolve(baseName + "." + extension);
            replaceFile(staged, target);
            return target.toAbsolutePath().normalize();
        } finally {
            deleteQuietly(staged);
        }
    }

    /** 当前生效的图片简历；不存在或读取失败时返回空。 */
    public synchronized Optional<ResumeImageInfo> getCurrentImage() {
        Optional<Path> current = findCurrentImagePath();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(describe(current.get()));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /** 当前生效图片简历的文件路径；不存在时返回空。 */
    public synchronized Optional<Path> getCurrentImagePath() {
        return findCurrentImagePath();
    }

    /** 图片简历的元信息（供管理页面展示）；不存在时只返回 exists=false。 */
    public Map<String, Object> getMetadata() {
        Optional<ResumeImageInfo> current = getCurrentImage();
        if (current.isEmpty()) {
            return Map.of("exists", false);
        }
        ResumeImageInfo info = current.get();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("exists", true);
        metadata.put("fileName", info.fileName());
        metadata.put("contentType", info.contentType());
        metadata.put("size", info.size());
        metadata.put("updatedAt", info.updatedAt());
        metadata.put("previewUrl", PREVIEW_URL);
        return metadata;
    }

    // ==================== 内部实现 ====================

    /** 上传文件的基本校验：非空、不超过大小上限（传输前先看 MultipartFile 元数据）。 */
    private void requireUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(EMPTY_UPLOAD_ERROR);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(SIZE_ERROR);
        }
    }

    /** 模板源文件校验：存在、可读、不超过上限。 */
    private void requireTemplateSource(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException(UNREADABLE_TEMPLATE_ERROR);
        }
        if (Files.size(source) > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(SIZE_ERROR);
        }
    }

    /** 上传内容写入同目录临时文件，并复核落盘后的真实大小。 */
    private Path stageUpload(MultipartFile file, Path directory, String prefix) throws IOException {
        Path staged = Files.createTempFile(directory, prefix, TEMP_SUFFIX);
        try {
            file.transferTo(staged);
            requireFileSize(staged);
            return staged;
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(staged);
            throw failure;
        }
    }

    /** 复制已有文件到同目录临时文件。 */
    private Path copyToStagingFile(Path source, Path directory, String prefix) throws IOException {
        Path staged = Files.createTempFile(directory, prefix, TEMP_SUFFIX);
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            requireFileSize(staged);
            return staged;
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(staged);
            throw failure;
        }
    }

    private void requireFileSize(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(SIZE_ERROR);
        }
    }

    /** 把临时文件提升为当前简历：按真实格式定扩展名、原子替换、清理其他格式旧文件。 */
    private ResumeImageInfo promoteToCurrentImage(Path staged) throws IOException {
        String extension = detectExtension(staged);
        Path target = storageDirectory.resolve(CURRENT_BASENAME + "." + extension);
        replaceFile(staged, target);
        deleteOtherFormats(target);
        return describe(target);
    }

    /** 原子替换；文件系统不支持原子移动时退回普通替换。 */
    private void replaceFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteOtherFormats(Path retained) throws IOException {
        for (String format : CURRENT_FORMATS) {
            Path candidate = storageDirectory.resolve(CURRENT_BASENAME + "." + format);
            if (!candidate.equals(retained)) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    /** 按固定优先级找当前简历文件（png > jpg > gif）。 */
    private Optional<Path> findCurrentImagePath() {
        return CURRENT_FORMATS.stream()
                .map(format -> storageDirectory.resolve(CURRENT_BASENAME + "." + format))
                .filter(Files::isRegularFile)
                .filter(Files::isReadable)
                .findFirst();
    }

    /**
     * 用 ImageIO 读取图片头判断真实格式并校验尺寸，避免只相信扩展名或 Content-Type。
     *
     * @return 规范扩展名：png / jpg / gif
     */
    private String detectExtension(Path image) throws IOException {
        ImageReader reader = null;
        try (ImageInputStream stream = ImageIO.createImageInputStream(image.toFile())) {
            if (stream == null) {
                throw new IllegalArgumentException("无法读取图片文件");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("文件不是有效的 PNG、JPG 或 GIF 图片");
            }
            reader = readers.next();
            reader.setInput(stream, true, true);
            if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                throw new IllegalArgumentException("图片尺寸无效");
            }
            return canonicalExtension(reader.getFormatName());
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private static String canonicalExtension(String imageFormat) {
        String format = imageFormat == null ? "" : imageFormat.toLowerCase(Locale.ROOT);
        if ("png".equals(format)) {
            return "png";
        }
        if ("gif".equals(format)) {
            return "gif";
        }
        if ("jpeg".equals(format) || "jpg".equals(format)) {
            return "jpg";
        }
        throw new IllegalArgumentException("仅支持 PNG、JPG、JPEG 和 GIF 图片");
    }

    /** 组装对外展示信息；扩展名不在这三种规范格式内时按 jpeg 处理（历史上只会有这三种）。 */
    private ResumeImageInfo describe(Path image) throws IOException {
        String fileName = image.getFileName().toString();
        String extension = extensionOf(fileName);
        return new ResumeImageInfo(
                image,
                fileName,
                contentTypeOf(extension),
                Files.size(image),
                Files.getLastModifiedTime(image).toMillis());
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentTypeOf(String extension) {
        if ("png".equals(extension)) {
            return "image/png";
        }
        if ("gif".equals(extension)) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
            // 临时文件清理失败不影响主流程，交给下一次上传覆盖
        }
    }

    /** 图片简历的对外描述。 */
    public record ResumeImageInfo(Path path, String fileName, String contentType, long size, long updatedAt) {
    }
}
