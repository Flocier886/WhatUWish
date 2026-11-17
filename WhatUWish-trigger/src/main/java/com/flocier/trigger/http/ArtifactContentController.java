package com.flocier.trigger.http;

import com.flocier.api.response.Response;
import com.flocier.application.agent.artifact.ArtifactContentApplicationService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/** 面向用户交付物的独立内容 API，不暴露内部 Artifact locator。 */
@RestController
@RequestMapping("/api/v1/artifacts/runs/{runId}/{artifactId}")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.OPTIONS})
@RequiredArgsConstructor
public class ArtifactContentController {
    private final ArtifactContentApplicationService artifactContentApplicationService;

    @GetMapping
    public ResponseEntity<Response<ArtifactDeliveryFile>> metadata(@PathVariable("runId") String runId,
                                                                    @PathVariable("artifactId") String artifactId) {
        try {
            return ResponseEntity.ok(success(artifactContentApplicationService.metadata(runId, artifactId)));
        } catch (ArtifactContentAccessException exception) {
            return accessError(exception.getState());
        }
    }

    @GetMapping("/preview")
    public ResponseEntity<StreamingResponseBody> preview(@PathVariable("runId") String runId,
                                                          @PathVariable("artifactId") String artifactId) {
        ArtifactContentAccessService.ArtifactPreview preview = artifactContentApplicationService.preview(runId, artifactId);
        StreamingResponseBody body = output -> {
            if (!preview.isBinary()) {
                output.write(preview.getText().getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (var input = preview.getStream()) {
                input.transferTo(output);
            }
        };
        MediaType contentType = preview.isBinary()
                ? parseMediaType(preview.getMimeType())
                : new MediaType("text", "plain", StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(contentType).body(body);
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("runId") String runId,
                                                           @PathVariable("artifactId") String artifactId) {
        ArtifactContentAccessService.ArtifactDownload download = artifactContentApplicationService.download(runId, artifactId);
        MediaType mediaType = parseMediaType(download.getMimeType());
        StreamingResponseBody body = output -> {
            try (var input = download.getStream()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok().contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(safeFileName(download.getFileName()), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    @ExceptionHandler(ArtifactContentAccessException.class)
    public ResponseEntity<Response<Void>> handleAccessException(ArtifactContentAccessException exception) {
        HttpStatus status = exception.getState() == ArtifactAccessState.FORBIDDEN ? HttpStatus.FORBIDDEN
                : exception.getState() == ArtifactAccessState.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Response.<Void>builder()
                .code(exception.getState().name()).info("ARTIFACT_UNAVAILABLE").build());
    }

    private MediaType parseMediaType(String value) {
        try {
            return value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String safeFileName(String value) {
        String name = value == null ? "artifact.bin" : value.replaceAll("[\\r\\n]", "").trim();
        return name.isBlank() ? "artifact.bin" : name;
    }

    private ResponseEntity<Response<ArtifactDeliveryFile>> accessError(ArtifactAccessState state) {
        HttpStatus status = state == ArtifactAccessState.FORBIDDEN ? HttpStatus.FORBIDDEN
                : state == ArtifactAccessState.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Response.<ArtifactDeliveryFile>builder()
                .code(state.name()).info("ARTIFACT_UNAVAILABLE").build());
    }

    private Response<ArtifactDeliveryFile> success(ArtifactDeliveryFile data) {
        return Response.<ArtifactDeliveryFile>builder().code("0000").info("查询成功").data(data).build();
    }
}
