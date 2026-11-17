package com.flocier.test.domain.ledger;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessSubject;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import com.flocier.domain.agent.runtime.artifact.content.TrustedLocalArtifactAccessAuthorizer;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.infrastructure.artifact.LocalArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.persistent.memory.InMemoryArtifactLedgerRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArtifactContentAccessServiceTest {
    private static final int PREVIEW_LIMIT_BYTES = 64 * 1024;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldReadDeliverableOnlyThroughRunBoundContentService() throws Exception {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        write("session-a/run-a/report.md", "# Research result");
        repository.save(artifact("run-a", "artifact-a", "session-a/run-a/report.md"));
        ArtifactContentAccessService service = service(repository);

        ArtifactDeliveryFile metadata = service.metadata("run-a", "artifact-a", trusted());

        assertEquals(ArtifactAccessState.AVAILABLE, metadata.getAccessState());
        assertTrue(metadata.isPreviewSupported());
        assertEquals("markdown", metadata.getPreview().getKind());
        assertEquals("v1", metadata.getPreview().getSchemaVersion());
        assertTrue(metadata.isDownloadSupported());
        assertEquals("# Research result", service.preview("run-a", "artifact-a", trusted()).getText());
        try (var stream = service.download("run-a", "artifact-a", trusted()).getStream()) {
            assertEquals("# Research result", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertState(ArtifactAccessState.NOT_FOUND,
                () -> service.metadata("run-b", "artifact-a", trusted()));
    }

    @Test
    public void shouldRejectDeletedInternalIntermediateAndPseudoArtifacts() {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        ArtifactLedgerEntity deleted = artifact("run-a", "deleted", "deleted.md");
        deleted.setDeleted(1);
        repository.save(deleted);
        ArtifactLedgerEntity internal = artifact("run-a", "internal", "internal.md");
        internal.setVisibility("internal");
        repository.save(internal);
        ArtifactLedgerEntity intermediate = artifact("run-a", "intermediate", "intermediate.md");
        intermediate.setArtifactRole("intermediate");
        repository.save(intermediate);
        ArtifactLedgerEntity llm = artifact("run-a", "llm", "llm.md");
        llm.setArtifactType("llm_response");
        repository.save(llm);
        ArtifactLedgerEntity tool = artifact("run-a", "tool", "tool.md");
        tool.setArtifactType("tool_result");
        repository.save(tool);
        ArtifactLedgerEntity inline = artifact("run-a", "inline", "inline.md");
        inline.setStorageType("inline_summary");
        repository.save(inline);
        ArtifactContentAccessService service = service(repository);

        for (String artifactId : new String[]{"deleted", "internal", "intermediate", "llm", "tool", "inline"}) {
            assertState(ArtifactAccessState.NOT_FOUND,
                    () -> service.metadata("run-a", artifactId, trusted()));
        }
    }

    @Test
    public void shouldRejectMissingLocatorAndUntrustedSubjectWithoutLeakingLocator() {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        repository.save(artifact("run-a", "missing-locator", null));
        repository.save(artifact("run-a", "private", "private/run-a/report.md"));
        ArtifactContentAccessService service = service(repository);

        ArtifactContentAccessException missing = assertState(ArtifactAccessState.UNAVAILABLE,
                () -> service.metadata("run-a", "missing-locator", trusted()));
        ArtifactContentAccessException forbidden = assertState(ArtifactAccessState.FORBIDDEN,
                () -> service.metadata("run-a", "private", new ArtifactAccessSubject("anonymous", false)));

        assertEquals("UNAVAILABLE", missing.getMessage());
        assertEquals("FORBIDDEN", forbidden.getMessage());
        assertFalse(missing.getMessage().contains("storage"));
        assertFalse(forbidden.getMessage().contains("private/run-a"));
    }

    @Test
    public void shouldKeepDownloadButDisablePreviewForUnsupportedAndOversizedFiles() throws Exception {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        write("session-a/run-a/data.json", "{\"ok\":true}");
        ArtifactLedgerEntity jsonArtifact = artifact("run-a", "json", "session-a/run-a/data.json");
        jsonArtifact.setFileName("data.json");
        jsonArtifact.setMimeType("application/json");
        repository.save(jsonArtifact);
        writeBytes("session-a/run-a/large.md", new byte[PREVIEW_LIMIT_BYTES + 1]);
        repository.save(artifact("run-a", "large", "session-a/run-a/large.md"));
        ArtifactContentAccessService service = service(repository);

        ArtifactDeliveryFile json = service.metadata("run-a", "json", trusted());
        ArtifactDeliveryFile large = service.metadata("run-a", "large", trusted());

        assertFalse(json.isPreviewSupported());
        assertEquals(null, json.getPreview());
        assertTrue(json.isDownloadSupported());
        assertFalse(large.isPreviewSupported());
        assertEquals(null, large.getPreview());
        assertTrue(large.isDownloadSupported());
        assertState(ArtifactAccessState.UNAVAILABLE,
                () -> service.preview("run-a", "json", trusted()));
        assertState(ArtifactAccessState.UNAVAILABLE,
                () -> service.preview("run-a", "large", trusted()));
    }

    @Test
    public void shouldPreviewHtmlAndSlidesThroughTheSameAuthorizedContentBoundary() throws Exception {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        write("session-a/run-a/report.html", "<h1>Report</h1>");
        ArtifactLedgerEntity html = artifact("run-a", "html", "session-a/run-a/report.html");
        html.setFileName("report.html");
        html.setMimeType("text/html");
        html.setMetadataJson("{\"preview\":{\"kind\":\"html\",\"schemaVersion\":\"v1\"}}");
        repository.save(html);
        write("session-a/run-a/slides.html", "<section>Slide 1</section>");
        ArtifactLedgerEntity slides = artifact("run-a", "slides", "session-a/run-a/slides.html");
        slides.setFileName("slides.html");
        slides.setMimeType("text/html+slides");
        slides.setMetadataJson("{\"preview\":{\"kind\":\"html-slides\",\"schemaVersion\":\"v1\"}}");
        repository.save(slides);
        ArtifactContentAccessService service = service(repository);

        ArtifactDeliveryFile htmlMetadata = service.metadata("run-a", "html", trusted());
        ArtifactDeliveryFile slidesMetadata = service.metadata("run-a", "slides", trusted());

        assertEquals("html", htmlMetadata.getPreview().getKind());
        assertEquals("html-slides", slidesMetadata.getPreview().getKind());
        assertEquals("<h1>Report</h1>", service.preview("run-a", "html", trusted()).getText());
        assertEquals("<section>Slide 1</section>", service.preview("run-a", "slides", trusted()).getText());
    }

    @Test
    public void shouldExposeDeclaredImageAsBinaryPreviewWithoutLeakingLocator() throws Exception {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        writeBytes("session-a/run-a/generated.png", png);
        ArtifactLedgerEntity image = artifact("run-a", "image-1", "session-a/run-a/generated.png");
        image.setArtifactName("generated.png");
        image.setFileName("generated.png");
        image.setArtifactType("image");
        image.setMimeType("image/png");
        image.setFileSize((long) png.length);
        image.setMetadataJson("{\"preview\":{\"kind\":\"image\",\"schemaVersion\":\"v1\"}}");
        repository.save(image);
        ArtifactContentAccessService service = service(repository);

        ArtifactDeliveryFile metadata = service.metadata("run-a", "image-1", trusted());
        ArtifactContentAccessService.ArtifactPreview preview = service.preview("run-a", "image-1", trusted());

        assertTrue(metadata.isPreviewSupported());
        assertEquals("image", metadata.getPreview().getKind());
        assertTrue(preview.isBinary());
        assertEquals("image/png", preview.getMimeType());
        try (var stream = preview.getStream()) {
            assertEquals(java.util.Arrays.toString(png), java.util.Arrays.toString(stream.readAllBytes()));
        }
        assertFalse(metadata.toString().contains("session-a/run-a/generated.png"));
    }

    @Test
    public void shouldReadOfficeAndCsvOnlyThroughDeclaredRunBoundPreview() throws Exception {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        byte[] zip = new byte[]{'P', 'K', 3, 4, 1, 2};
        String csvText = "地区,金额\n华东,42";
        for (String name : new String[]{"report.docx", "sales.xlsx"}) {
            String key = "session-a/run-a/" + name;
            writeBytes(key, zip);
            ArtifactLedgerEntity office = artifact("run-a", name, key);
            office.setFileName(name);
            office.setMimeType(name.endsWith("docx")
                    ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            office.setMetadataJson("{\"preview\":{\"kind\":\""
                    + (name.endsWith("docx") ? "docx" : "spreadsheet") + "\",\"schemaVersion\":\"v1\"}}");
            repository.save(office);
        }
        write("session-a/run-a/sales.csv", csvText);
        ArtifactLedgerEntity csv = artifact("run-a", "sales.csv", "session-a/run-a/sales.csv");
        csv.setFileName("sales.csv");
        csv.setMimeType("text/csv");
        csv.setMetadataJson("{\"preview\":{\"kind\":\"spreadsheet\",\"schemaVersion\":\"v1\"}}");
        repository.save(csv);
        ArtifactContentAccessService service = service(repository);

        assertEquals("docx", service.metadata("run-a", "report.docx", trusted()).getPreview().getKind());
        assertEquals("spreadsheet", service.metadata("run-a", "sales.xlsx", trusted()).getPreview().getKind());
        assertEquals("spreadsheet", service.metadata("run-a", "sales.csv", trusted()).getPreview().getKind());
        try (var stream = service.preview("run-a", "report.docx", trusted()).getStream()) {
            assertEquals(zip.length, stream.readAllBytes().length);
        }
        try (var stream = service.preview("run-a", "sales.xlsx", trusted()).getStream()) {
            assertEquals(zip.length, stream.readAllBytes().length);
        }
        assertEquals(csvText, service.preview("run-a", "sales.csv", trusted()).getText());
        assertState(ArtifactAccessState.NOT_FOUND,
                () -> service.preview("run-b", "report.docx", trusted()));
        assertState(ArtifactAccessState.FORBIDDEN,
                () -> service.preview("run-a", "report.docx", new ArtifactAccessSubject("anonymous", false)));
        Files.delete(root().resolve("session-a/run-a/sales.xlsx"));
        assertFalse(service.metadata("run-a", "sales.xlsx", trusted()).isPreviewSupported());
    }

    @Test
    public void shouldRejectSvgAndMissingPhysicalImagePreviewWithoutLeakingLocator() {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        try {
            write("session-a/run-a/generated.svg", "<svg></svg>");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        ArtifactLedgerEntity svg = artifact("run-a", "svg-1", "session-a/run-a/generated.svg");
        svg.setArtifactName("generated.svg");
        svg.setFileName("generated.svg");
        svg.setArtifactType("image");
        svg.setMimeType("image/svg+xml");
        svg.setFileSize(12L);
        svg.setMetadataJson("{\"preview\":{\"kind\":\"image\",\"schemaVersion\":\"v1\"}}");
        repository.save(svg);
        ArtifactLedgerEntity missing = artifact("run-a", "image-missing", "session-a/run-a/missing.png");
        missing.setArtifactType("image");
        missing.setMimeType("image/png");
        missing.setFileSize(12L);
        missing.setMetadataJson("{\"preview\":{\"kind\":\"image\",\"schemaVersion\":\"v1\"}}");
        repository.save(missing);
        ArtifactContentAccessService service = service(repository);

        assertFalse(service.metadata("run-a", "svg-1", trusted()).isPreviewSupported());
        assertTrue(service.metadata("run-a", "svg-1", trusted()).isDownloadSupported());
        assertState(ArtifactAccessState.UNAVAILABLE, () -> service.preview("run-a", "svg-1", trusted()));
        ArtifactDeliveryFile unavailable = service.metadata("run-a", "image-missing", trusted());
        assertEquals(ArtifactAccessState.UNAVAILABLE, unavailable.getAccessState());
        assertFalse(unavailable.isPreviewSupported());
        assertFalse(unavailable.toString().contains("session-a/run-a/missing.png"));
    }

    @Test
    public void shouldReturnUnavailableCapabilitiesForMissingPhysicalContent() {
        InMemoryArtifactLedgerRepository repository = new InMemoryArtifactLedgerRepository();
        repository.save(artifact("run-a", "missing-file", "session-a/run-a/missing.md"));

        ArtifactDeliveryFile metadata = service(repository).metadata("run-a", "missing-file", trusted());

        assertEquals(ArtifactAccessState.UNAVAILABLE, metadata.getAccessState());
        assertFalse(metadata.isPreviewSupported());
        assertEquals(null, metadata.getPreview());
        assertFalse(metadata.isDownloadSupported());
        assertFalse(metadata.toString().contains("storageKey"));
        assertFalse(metadata.toString().contains("session-a/run-a"));
    }

    private ArtifactContentAccessException assertState(ArtifactAccessState state, Runnable action) {
        ArtifactContentAccessException exception = assertThrows(ArtifactContentAccessException.class, action::run);
        assertEquals(state, exception.getState());
        return exception;
    }

    private ArtifactAccessSubject trusted() {
        return ArtifactAccessSubject.localTrustedSubject();
    }

    private ArtifactContentAccessService service(InMemoryArtifactLedgerRepository repository) {
        return new ArtifactContentAccessService(repository, new TrustedLocalArtifactAccessAuthorizer(),
                new RoutingArtifactContentStore(java.util.List.of(
                        new LocalArtifactContentStoreBackend(root().toString()))));
    }

    private void write(String key, String content) throws Exception {
        writeBytes(key, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(String key, byte[] content) throws Exception {
        Path target = root().resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private ArtifactLedgerEntity artifact(String runId, String artifactId, String key) {
        return ArtifactLedgerEntity.builder().runId(runId).sessionId("session-a").artifactId(artifactId)
                .artifactName("report.md").fileName("report.md").artifactType("report").artifactRole("output")
                .visibility("visible").storageType("file_ref").storageKey(key).storageRef(key)
                .mimeType("text/markdown").deleted(0).build();
    }

    private Path root() {
        return temporaryFolder.getRoot().toPath();
    }
}
