package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewPolicy;
import org.junit.Assert;
import org.junit.Test;

public class ArtifactPreviewPolicyTest {

    @Test
    public void shouldDescribeOnlyAvailableMarkdownWithinBudget() {
        ArtifactPreviewDescriptor markdown = ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/markdown", "report.bin", 128L);
        ArtifactPreviewDescriptor extensionFallback = ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, null, "REPORT.MD", 128L);

        Assert.assertEquals(ArtifactPreviewDescriptor.markdownV1(), markdown);
        Assert.assertEquals(ArtifactPreviewDescriptor.markdownV1(), extensionFallback);
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "application/json", "data.json", 128L));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.NOT_FOUND, "text/markdown", "report.md", 128L));
    }

    @Test
    public void shouldFailClosedForMissingInvalidAndOversizedSizes() {
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/markdown", "report.md", null));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/markdown", "report.md", -1L));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/markdown", "report.md",
                ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES + 1));
        Assert.assertNotNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/markdown", "report.md",
                ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES));
    }

    @Test
    public void shouldRequireExplicitAndMatchingProfilesForHtmlAndSlides() {
        Assert.assertEquals(ArtifactPreviewDescriptor.htmlV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/html", "report.html", 128L,
                ArtifactPreviewDescriptor.htmlV1()));
        Assert.assertEquals(ArtifactPreviewDescriptor.htmlV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/html", "canvas.html",
                ArtifactPreviewPolicy.HTML_PREVIEW_LIMIT_BYTES, ArtifactPreviewDescriptor.htmlV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/html", "canvas.html",
                ArtifactPreviewPolicy.HTML_PREVIEW_LIMIT_BYTES + 1, ArtifactPreviewDescriptor.htmlV1()));
        Assert.assertEquals(ArtifactPreviewDescriptor.htmlSlidesV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/html+slides", "slides.html", 128L,
                ArtifactPreviewDescriptor.htmlSlidesV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/html", "report.html", 128L));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/html", "report.html", 128L,
                ArtifactPreviewDescriptor.htmlSlidesV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/html+slides", "slides.html", 128L,
                ArtifactPreviewDescriptor.htmlV1()));
    }

    @Test
    public void shouldDescribeOnlyExplicitAndBudgetedRasterImageProfile() {
        Assert.assertEquals(ArtifactPreviewDescriptor.imageV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "image/png", "generated.png", 128L,
                ArtifactPreviewDescriptor.imageV1()));
        Assert.assertEquals(ArtifactPreviewDescriptor.imageV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "image/jpeg; charset=binary", "generated.jpeg",
                ArtifactPreviewPolicy.IMAGE_PREVIEW_LIMIT_BYTES, ArtifactPreviewDescriptor.imageV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "image/png", "generated.png", 128L));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "image/svg+xml", "generated.svg", 128L,
                ArtifactPreviewDescriptor.imageV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "image/png", "generated.png",
                ArtifactPreviewPolicy.IMAGE_PREVIEW_LIMIT_BYTES + 1, ArtifactPreviewDescriptor.imageV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.UNAVAILABLE, "image/png", "generated.png", 128L,
                ArtifactPreviewDescriptor.imageV1()));
    }

    @Test
    public void shouldPreviewOnlyDeclaredValidPdfWithinBudget() {
        Assert.assertEquals(ArtifactPreviewDescriptor.pdfV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "application/pdf", "report.pdf", 1024L,
                ArtifactPreviewDescriptor.pdfV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "application/pdf", "report.pdf", 1024L));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "application/pdf", "report.txt", 1024L,
                ArtifactPreviewDescriptor.pdfV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "application/pdf", "report.pdf",
                ArtifactPreviewPolicy.PDF_PREVIEW_LIMIT_BYTES + 1, ArtifactPreviewDescriptor.pdfV1()));
        Assert.assertTrue(ArtifactPreviewPolicy.matchesPdfSignature("%PDF-1.7".getBytes()));
        Assert.assertFalse(ArtifactPreviewPolicy.matchesPdfSignature("not a pdf".getBytes()));
    }

    @Test
    public void textProfileRequiresSafeMimeAndBudget() {
        Assert.assertEquals(ArtifactPreviewDescriptor.textV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/plain", "notes.txt", 12L,
                ArtifactPreviewDescriptor.textV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "application/octet-stream", "notes.txt", 12L,
                ArtifactPreviewDescriptor.textV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/plain", "notes.txt",
                ArtifactPreviewPolicy.PREVIEW_LIMIT_BYTES + 1, ArtifactPreviewDescriptor.textV1()));
        Assert.assertFalse(ArtifactPreviewPolicy.isUtf8Text(new byte[]{(byte) 0xc3, (byte) 0x28}));
        Assert.assertFalse(ArtifactPreviewPolicy.matchesRasterSignature("image/png", new byte[]{1, 2, 3}));
    }

    @Test
    public void officeProfilesRequireExactTypeNameBudgetAndAvailableContent() {
        String word = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        String excel = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        Assert.assertEquals(ArtifactPreviewDescriptor.docxV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, word, "report.docx", 1024L, ArtifactPreviewDescriptor.docxV1()));
        Assert.assertEquals(ArtifactPreviewDescriptor.spreadsheetV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, excel, "sales.xlsx", 1024L, ArtifactPreviewDescriptor.spreadsheetV1()));
        Assert.assertEquals(ArtifactPreviewDescriptor.spreadsheetV1(), ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/csv; charset=UTF-8", "sales.csv", 1024L,
                ArtifactPreviewDescriptor.spreadsheetV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, word, "report.doc", 1024L, ArtifactPreviewDescriptor.docxV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, excel, "sales.csv", 1024L, ArtifactPreviewDescriptor.spreadsheetV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, word, "report.docx", 1024L));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.UNAVAILABLE, word, "report.docx", 1024L, ArtifactPreviewDescriptor.docxV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, word, "report.docx", null, ArtifactPreviewDescriptor.docxV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, word, "report.docx",
                ArtifactPreviewPolicy.OFFICE_PREVIEW_LIMIT_BYTES + 1, ArtifactPreviewDescriptor.docxV1()));
        Assert.assertNull(ArtifactPreviewPolicy.describe(
                ArtifactAccessState.AVAILABLE, "text/csv", "sales.csv",
                ArtifactPreviewPolicy.CSV_PREVIEW_LIMIT_BYTES + 1, ArtifactPreviewDescriptor.spreadsheetV1()));
        Assert.assertTrue(ArtifactPreviewPolicy.matchesOfficeSignature(new byte[]{'P', 'K', 3, 4, 0}));
        Assert.assertFalse(ArtifactPreviewPolicy.matchesOfficeSignature("not an office file".getBytes()));
    }
}
