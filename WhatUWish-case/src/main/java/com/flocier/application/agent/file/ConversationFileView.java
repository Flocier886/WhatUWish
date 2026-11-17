package com.flocier.application.agent.file;

import com.flocier.domain.agent.runtime.artifact.content.ArtifactPreviewDescriptor;

import java.time.Instant;

/** 浏览器可见的文件身份、状态与 Reader 能力；不包含对象存储定位信息或正文。 */
public record ConversationFileView(String fileId, String sessionId, String name, String mimeType,
                                   long size, String status, Instant createdAt,
                                   ArtifactPreviewDescriptor preview, boolean downloadSupported) {
}
