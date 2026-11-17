package com.flocier.domain.agent.runtime.artifact.content;

/** Artifact Ledger 内部使用的正式存储类型。 */
public final class ArtifactStorageType {
    public static final String LOCAL = "local_artifact_storage";
    public static final String S3 = "s3_object_storage";

    /** 早期正式文件曾使用该类型，显式映射到 Local Backend 以保持只读兼容。 */
    public static final String LEGACY_FILE_REF = "file_ref";

    private ArtifactStorageType() {
    }
}
