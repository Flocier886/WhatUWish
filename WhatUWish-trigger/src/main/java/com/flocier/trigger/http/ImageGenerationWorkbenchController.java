package com.flocier.trigger.http;

import com.flocier.api.response.Response;
import com.flocier.application.agent.artifact.ArtifactContentApplicationService;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationCommand;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationException;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationExecutionService;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationHistoryPage;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationHistoryService;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageGenerationResult;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageInput;
import com.flocier.application.agent.imagegeneration.workbench.WorkbenchImageArtifact;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactAccessState;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessException;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactContentAccessService;
import com.flocier.domain.agent.runtime.artifact.content.ArtifactDeliveryFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 图片工作台直接生成入口，不创建 Agent Runtime SSE 订阅或 Run。 */
@RestController
@RequestMapping("/api/v1/image-generation-workbench")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@RequiredArgsConstructor
public class ImageGenerationWorkbenchController {
    private final WorkbenchImageGenerationExecutionService executionService;
    private final WorkbenchImageGenerationHistoryService historyService;
    private final ArtifactContentApplicationService artifactContentApplicationService;

    @PostMapping(value = "generations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Response<WorkbenchImageGenerationResult> generate(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("prompt") String prompt,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "outputFileName", required = false) String outputFileName,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "n", required = false) Integer count,
            @RequestParam(name = "references", required = false) List<MultipartFile> references,
            @RequestParam(name = "masks", required = false) List<MultipartFile> masks
    ) throws IOException {
        WorkbenchImageGenerationResult result = executionService.execute(WorkbenchImageGenerationCommand.builder()
                .sessionId(sessionId)
                .prompt(prompt)
                .mode(mode)
                .outputFileName(outputFileName)
                .size(size)
                .count(count)
                .references(inputs(references))
                .masks(inputs(masks))
                .build());
        return Response.<WorkbenchImageGenerationResult>builder().code("0000").info("请求完成").data(result).build();
    }

    @GetMapping(value = "history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Response<WorkbenchImageGenerationHistoryPage> history(
            @RequestParam("sessionId") String sessionId,
            @RequestParam(name = "pageNo", required = false) Integer pageNo,
            @RequestParam(name = "pageSize", required = false) Integer pageSize
    ) {
        return Response.<WorkbenchImageGenerationHistoryPage>builder()
                .code("0000")
                .info("查询成功")
                .data(historyService.query(sessionId, pageNo, pageSize))
                .build();
    }

    @GetMapping(value = "executions/{executionId}/artifacts/{artifactId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response<WorkbenchImageArtifact>> metadata(
            @PathVariable("executionId") String executionId,
            @PathVariable("artifactId") String artifactId
    ) {
        ArtifactDeliveryFile delivery = artifactContentApplicationService.metadata(executionId, artifactId);
        return ResponseEntity.ok(Response.<WorkbenchImageArtifact>builder()
                .code("0000")
                .info("查询成功")
                .data(toWorkbenchArtifact(delivery))
                .build());
    }

    @GetMapping("executions/{executionId}/artifacts/{artifactId}/preview")
    public ResponseEntity<StreamingResponseBody> preview(
            @PathVariable("executionId") String executionId,
            @PathVariable("artifactId") String artifactId
    ) {
        ArtifactContentAccessService.ArtifactPreview preview = artifactContentApplicationService.preview(executionId, artifactId);
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
                ? mediaType(preview.getMimeType())
                : new MediaType("text", "plain", StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(contentType).body(body);
    }

    @GetMapping("executions/{executionId}/artifacts/{artifactId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable("executionId") String executionId,
            @PathVariable("artifactId") String artifactId
    ) {
        ArtifactContentAccessService.ArtifactDownload download = artifactContentApplicationService.download(executionId, artifactId);
        StreamingResponseBody body = output -> {
            try (var input = download.getStream()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .contentType(mediaType(download.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(safeFileName(download.getFileName()), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    @ExceptionHandler(WorkbenchImageGenerationException.class)
    public ResponseEntity<Response<Void>> handleWorkbenchException(WorkbenchImageGenerationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Response.<Void>builder()
                .code(exception.getErrorCode())
                .info(exception.getMessage())
                .build());
    }

    @ExceptionHandler(ArtifactContentAccessException.class)
    public ResponseEntity<Response<Void>> handleArtifactAccessException(ArtifactContentAccessException exception) {
        HttpStatus status = exception.getState() == ArtifactAccessState.FORBIDDEN ? HttpStatus.FORBIDDEN
                : exception.getState() == ArtifactAccessState.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Response.<Void>builder()
                .code(exception.getState().name())
                .info("ARTIFACT_UNAVAILABLE")
                .build());
    }

    private List<WorkbenchImageInput> inputs(List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) return List.of();
        java.util.ArrayList<WorkbenchImageInput> values = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new WorkbenchImageGenerationException("image_generation_request_invalid", "上传图片不能为空。");
            }
            values.add(WorkbenchImageInput.builder()
                    .fileName(file.getOriginalFilename())
                    .declaredMimeType(file.getContentType())
                    .content(file.getBytes())
                    .build());
        }
        return List.copyOf(values);
    }

    private MediaType mediaType(String value) {
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

    private WorkbenchImageArtifact toWorkbenchArtifact(ArtifactDeliveryFile delivery) {
        return WorkbenchImageArtifact.builder()
                .artifactId(delivery.getArtifactId())
                .name(delivery.getName())
                .mimeType(delivery.getMimeType())
                .size(delivery.getSize())
                .accessState(delivery.getAccessState().name())
                .preview(delivery.getPreview())
                .previewSupported(delivery.isPreviewSupported())
                .downloadSupported(delivery.isDownloadSupported())
                .build();
    }
}
