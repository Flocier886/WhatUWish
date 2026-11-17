package com.flocier.trigger.http;

import com.flocier.api.response.Response;
import com.flocier.application.agent.file.ConversationFileAccessException;
import com.flocier.application.agent.file.ConversationFileService;
import com.flocier.application.agent.file.ConversationFileSessionService;
import com.flocier.application.agent.file.ConversationFileView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 本地单用户文件 HTTP 边界：按会话和文件身份读取安全元数据与受控内容。 */
@RestController
@RequestMapping("/api/v1/conversation-files")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST,
        RequestMethod.DELETE, RequestMethod.OPTIONS})
@RequiredArgsConstructor
public class ConversationFileController {
    private final ConversationFileSessionService sessions;
    private final ConversationFileService files;

    @PostMapping("/sessions")
    public Response<ConversationFileSessionService.IssuedSession> createSession() {
        return success(sessions.create());
    }

    @GetMapping("/sessions/{sessionId}")
    public Response<SessionStatus> session(@PathVariable("sessionId") String sessionId) {
        sessions.requireAccess(sessionId);
        return success(new SessionStatus(sessionId));
    }

    /** Run 创建前上传文件；只把 multipart 原始流交给应用服务，不接受客户端指定的存储地址。 */
    @PostMapping(value = "/sessions/{sessionId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<ConversationFileView> upload(@PathVariable("sessionId") String sessionId,
                                                 @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null) throw new ConversationFileAccessException("FILE_EMPTY");
        try (var stream = file.getInputStream()) {
            return success(files.upload(sessionId, file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), stream));
        }
    }

    @GetMapping("/sessions/{sessionId}/files")
    public Response<List<ConversationFileView>> list(@PathVariable("sessionId") String sessionId) {
        return success(files.list(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/files/{fileId}")
    public Response<ConversationFileView> metadata(@PathVariable("sessionId") String sessionId, @PathVariable("fileId") String fileId) {
        return success(files.metadata(sessionId, fileId));
    }

    @DeleteMapping("/sessions/{sessionId}/files/{fileId}")
    public Response<Void> remove(@PathVariable("sessionId") String sessionId, @PathVariable("fileId") String fileId) {
        files.remove(sessionId, fileId);
        return success(null);
    }

    /** 预览须先通过会话授权和服务端类型策略；响应禁止 MIME 嗅探与缓存。 */
    @GetMapping("/sessions/{sessionId}/files/{fileId}/preview")
    public ResponseEntity<StreamingResponseBody> preview(@PathVariable("sessionId") String sessionId, @PathVariable("fileId") String fileId) {
        ConversationFileService.Preview preview = files.preview(sessionId, fileId);
        StreamingResponseBody body = output -> {
            try (var input = preview.stream()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(preview.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /** 原字节流以附件形式下载，不把内部 storageKey 或对象存储 URL 暴露给浏览器。 */
    @GetMapping("/sessions/{sessionId}/files/{fileId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("sessionId") String sessionId, @PathVariable("fileId") String fileId) {
        ConversationFileView file = files.metadata(sessionId, fileId);
        var input = files.download(sessionId, fileId);
        StreamingResponseBody body = output -> {
            try (input) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @ExceptionHandler(ConversationFileAccessException.class)
    public ResponseEntity<Response<Void>> handle(ConversationFileAccessException exception) {
        String code = exception.getCode();
        HttpStatus status = code.endsWith("FORBIDDEN") ? HttpStatus.FORBIDDEN
                : code.endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : code.endsWith("TOO_LARGE") ? HttpStatus.PAYLOAD_TOO_LARGE
                : code.endsWith("BUSY") ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Response.<Void>builder().code(code).info(code).build());
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code("0000").info("成功").data(data).build();
    }

    public record SessionStatus(String sessionId) {
    }
}
