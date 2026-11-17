package com.flocier.domain.agent.runtime.artifact.content;

import lombok.Value;

/** 描述文件服务返回的受控预览内容形态，不绑定具体前端组件。 */
@Value
public class ArtifactPreviewDescriptor {
    public static final String MARKDOWN_KIND = "markdown";
    public static final String HTML_KIND = "html";
    public static final String HTML_SLIDES_KIND = "html-slides";
    public static final String IMAGE_KIND = "image";
    public static final String PDF_KIND = "pdf";
    public static final String DOCX_KIND = "docx";
    public static final String SPREADSHEET_KIND = "spreadsheet";
    public static final String TEXT_KIND = "text";
    public static final String V1 = "v1";

    String kind;
    String schemaVersion;

    public static ArtifactPreviewDescriptor markdownV1() {
        return new ArtifactPreviewDescriptor(MARKDOWN_KIND, V1);
    }

    public static ArtifactPreviewDescriptor htmlV1() {
        return new ArtifactPreviewDescriptor(HTML_KIND, V1);
    }

    public static ArtifactPreviewDescriptor htmlSlidesV1() {
        return new ArtifactPreviewDescriptor(HTML_SLIDES_KIND, V1);
    }

    public static ArtifactPreviewDescriptor imageV1() {
        return new ArtifactPreviewDescriptor(IMAGE_KIND, V1);
    }

    public static ArtifactPreviewDescriptor pdfV1() {
        return new ArtifactPreviewDescriptor(PDF_KIND, V1);
    }

    public static ArtifactPreviewDescriptor docxV1() {
        return new ArtifactPreviewDescriptor(DOCX_KIND, V1);
    }

    public static ArtifactPreviewDescriptor spreadsheetV1() {
        return new ArtifactPreviewDescriptor(SPREADSHEET_KIND, V1);
    }

    public static ArtifactPreviewDescriptor textV1() {
        return new ArtifactPreviewDescriptor(TEXT_KIND, V1);
    }
}
