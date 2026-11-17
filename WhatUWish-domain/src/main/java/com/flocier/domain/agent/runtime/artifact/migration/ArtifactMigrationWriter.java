package com.flocier.domain.agent.runtime.artifact.migration;

import java.io.InputStream;

/** 只供显式迁移使用的对象写入 Port，不属于用户文件读取能力。 */
public interface ArtifactMigrationWriter {
    String targetKey(String artifactId);

    void upload(String artifactId, String targetKey, String mimeType, String sha256,
                long contentLength, InputStream content);
}
