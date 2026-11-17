package com.flocier.domain.agent.runtime.artifact.content;

import org.apache.commons.lang3.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Artifact 预览格式、访问状态和大小预算的唯一判定策略。 */
public final class ArtifactPreviewPolicy {
    /** 文本预览在内存中读取，维持较小预算以避免长文直接进入 Reader 请求。 */
    public static final long PREVIEW_LIMIT_BYTES = 64L * 1024L;
    /**
     * 图片预览使用受控二进制流，不会作为文本一次性读入内存；单独保留更贴近正式图片交付的大小预算。
     * 超出预算的图片仍可下载，但不进入内联预览，避免异常大对象长期占用浏览器 Reader。
     */
    public static final long IMAGE_PREVIEW_LIMIT_BYTES = 20L * 1024L * 1024L;
    public static final long PDF_PREVIEW_LIMIT_BYTES = 20L * 1024L * 1024L;
    public static final long OFFICE_PREVIEW_LIMIT_BYTES = 10L * 1024L * 1024L;
    public static final long CSV_PREVIEW_LIMIT_BYTES = 2L * 1024L * 1024L;
    /** 完整 HTML 页面可能包含内联样式和脚本；Reader 仍在隔离 iframe 内运行。 */
    public static final long HTML_PREVIEW_LIMIT_BYTES = 1024L * 1024L;

    private ArtifactPreviewPolicy() {
    }

    public static ArtifactPreviewDescriptor describe(ArtifactAccessState accessState, String mimeType,
                                                     String fileName, Long size) {
        if (!isAvailableSize(accessState, size, PREVIEW_LIMIT_BYTES)) {
            return null;
        }
        if (isMarkdown(mimeType, fileName)) {
            return ArtifactPreviewDescriptor.markdownV1();
        }
        return null;
    }

    /**
     * 正式 Artifact 可显式声明 preview profile；未知、版本不匹配或与内容类型冲突时一律拒绝。
     * 未声明 profile 的历史 Markdown Artifact 仍按既有兼容规则处理。
     */
    public static ArtifactPreviewDescriptor describe(ArtifactAccessState accessState, String mimeType,
                                                     String fileName, Long size,
                                                     ArtifactPreviewDescriptor declared) {
        if (declared == null) {
            return describe(accessState, mimeType, fileName, size);
        }
        if (accessState != ArtifactAccessState.AVAILABLE || size == null || size < 0
                || !ArtifactPreviewDescriptor.V1.equals(declared.getSchemaVersion())) {
            return null;
        }
        if (ArtifactPreviewDescriptor.MARKDOWN_KIND.equals(declared.getKind()) && isMarkdown(mimeType, fileName)) {
            return ArtifactPreviewDescriptor.markdownV1();
        }
        if (ArtifactPreviewDescriptor.HTML_KIND.equals(declared.getKind()) && isHtml(mimeType, fileName)
                && !isHtmlSlides(mimeType, fileName)) {
            return isAvailableSize(accessState, size, HTML_PREVIEW_LIMIT_BYTES)
                    ? ArtifactPreviewDescriptor.htmlV1() : null;
        }
        if (ArtifactPreviewDescriptor.HTML_SLIDES_KIND.equals(declared.getKind()) && isHtmlSlides(mimeType, fileName)) {
            return ArtifactPreviewDescriptor.htmlSlidesV1();
        }
        if (ArtifactPreviewDescriptor.IMAGE_KIND.equals(declared.getKind()) && isPreviewableRasterImage(mimeType)) {
            return isAvailableSize(accessState, size, IMAGE_PREVIEW_LIMIT_BYTES)
                    ? ArtifactPreviewDescriptor.imageV1() : null;
        }
        if (ArtifactPreviewDescriptor.PDF_KIND.equals(declared.getKind()) && isPdf(mimeType, fileName)) {
            return isAvailableSize(accessState, size, PDF_PREVIEW_LIMIT_BYTES)
                    ? ArtifactPreviewDescriptor.pdfV1() : null;
        }
        if (ArtifactPreviewDescriptor.DOCX_KIND.equals(declared.getKind()) && isDocx(mimeType, fileName)) {
            return isAvailableSize(accessState, size, OFFICE_PREVIEW_LIMIT_BYTES)
                    ? ArtifactPreviewDescriptor.docxV1() : null;
        }
        if (ArtifactPreviewDescriptor.SPREADSHEET_KIND.equals(declared.getKind())) {
            if (isXlsx(mimeType, fileName)) return isAvailableSize(accessState, size, OFFICE_PREVIEW_LIMIT_BYTES)
                    ? ArtifactPreviewDescriptor.spreadsheetV1() : null;
            if (isCsv(mimeType, fileName)) return isAvailableSize(accessState, size, CSV_PREVIEW_LIMIT_BYTES)
                    ? ArtifactPreviewDescriptor.spreadsheetV1() : null;
        }
        if (ArtifactPreviewDescriptor.TEXT_KIND.equals(declared.getKind()) && isPlainText(mimeType)) {
            return isAvailableSize(accessState, size, PREVIEW_LIMIT_BYTES)
                    ? ArtifactPreviewDescriptor.textV1() : null;
        }
        return null;
    }

    public static boolean isPlainText(String mimeType) {
        String mime = StringUtils.defaultString(mimeType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "text/plain".equals(mime) || "text/csv".equals(mime);
    }

    public static boolean isPdf(String mimeType, String fileName) {
        String mime = StringUtils.defaultString(mimeType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String name = StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT);
        return "application/pdf".equals(mime) && name.endsWith(".pdf");
    }

    public static boolean isDocx(String mimeType, String fileName) {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(normalizedMime(mimeType))
                && StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    public static boolean isXlsx(String mimeType, String fileName) {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(normalizedMime(mimeType))
                && StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    public static boolean isCsv(String mimeType, String fileName) {
        return "text/csv".equals(normalizedMime(mimeType))
                && StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    public static boolean matchesOfficeSignature(byte[] bytes) {
        return startsWith(bytes, new byte[]{'P', 'K', 3, 4});
    }

    private static String normalizedMime(String mimeType) {
        return StringUtils.defaultString(mimeType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    public static boolean matchesPdfSignature(byte[] bytes) {
        return startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean isUtf8Text(byte[] content) {
        if (content == null) return false;
        for (byte value : content) if (value == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }

    public static boolean matchesRasterSignature(String mimeType, byte[] bytes) {
        if (bytes == null) return false;
        String mime = StringUtils.defaultString(mimeType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("image/png".equals(mime)) return startsWith(bytes, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        if ("image/jpeg".equals(mime)) return startsWith(bytes, new byte[]{(byte) 255, (byte) 216, (byte) 255});
        if ("image/gif".equals(mime)) return startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII));
        if ("image/bmp".equals(mime)) return startsWith(bytes, "BM".getBytes(StandardCharsets.US_ASCII));
        return "image/webp".equals(mime) && bytes.length >= 12
                && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    public static boolean isMarkdown(String mimeType, String fileName) {
        String mime = StringUtils.defaultString(mimeType).trim().toLowerCase(Locale.ROOT);
        String name = StringUtils.defaultString(fileName).trim().toLowerCase(Locale.ROOT);
        return "text/markdown".equals(mime) || "text/x-markdown".equals(mime) || name.endsWith(".md");
    }

    public static boolean isHtml(String mimeType, String fileName) {
        String mime = StringUtils.defaultString(mimeType).trim().toLowerCase(Locale.ROOT);
        String name = StringUtils.defaultString(fileName).trim().toLowerCase(Locale.ROOT);
        return "text/html".equals(mime) || "application/xhtml+xml".equals(mime)
                || name.endsWith(".html") || name.endsWith(".htm");
    }

    /** Slides 由 Report Python 协议以受控 MIME 声明，不能只根据文件名猜测。 */
    public static boolean isHtmlSlides(String mimeType, String fileName) {
        String mime = StringUtils.defaultString(mimeType).trim().toLowerCase(Locale.ROOT);
        return "text/html+slides".equals(mime) || "application/vnd.flocier.html-slides".equals(mime);
    }

    /** SVG 不走本阶段预览：它可携带脚本/外部引用，当前仅允许受控下载。 */
    public static boolean isPreviewableRasterImage(String mimeType) {
        String mime = StringUtils.defaultString(mimeType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "image/png".equals(mime) || "image/jpeg".equals(mime) || "image/webp".equals(mime)
                || "image/gif".equals(mime) || "image/bmp".equals(mime);
    }

    public static boolean isBinaryPreview(ArtifactPreviewDescriptor descriptor) {
        return descriptor != null && (ArtifactPreviewDescriptor.IMAGE_KIND.equals(descriptor.getKind())
                || ArtifactPreviewDescriptor.PDF_KIND.equals(descriptor.getKind())
                || ArtifactPreviewDescriptor.DOCX_KIND.equals(descriptor.getKind())
                || ArtifactPreviewDescriptor.SPREADSHEET_KIND.equals(descriptor.getKind()))
                && ArtifactPreviewDescriptor.V1.equals(descriptor.getSchemaVersion());
    }

    private static boolean isAvailableSize(ArtifactAccessState accessState, Long size, long limitBytes) {
        return accessState == ArtifactAccessState.AVAILABLE && size != null && size >= 0 && size <= limitBytes;
    }
}
