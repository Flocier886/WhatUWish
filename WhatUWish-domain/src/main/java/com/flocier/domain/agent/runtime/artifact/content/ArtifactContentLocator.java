package com.flocier.domain.agent.runtime.artifact.content;

/** 仅允许在内容访问服务与存储 Adapter 间传播的内部定位信息。 */
public class ArtifactContentLocator {
    private final String storageType;
    private final String storageKey;
    private final String storageRef;

    public ArtifactContentLocator(String storageType, String storageKey, String storageRef) {
        this.storageType = storageType;
        this.storageKey = storageKey;
        this.storageRef = storageRef;
    }

    public String getStorageType() {
        return storageType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getStorageRef() {
        return storageRef;
    }

    public boolean isEmpty() {
        return (storageKey == null || storageKey.isBlank()) && (storageRef == null || storageRef.isBlank());
    }

    public String preferredKey() {
        return storageKey != null && !storageKey.isBlank() ? storageKey : storageRef;
    }
}
