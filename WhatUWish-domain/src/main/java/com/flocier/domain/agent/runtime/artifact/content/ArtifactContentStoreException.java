package com.flocier.domain.agent.runtime.artifact.content;

/** 存储 Adapter 向内容访问服务报告的受控失败。 */
public class ArtifactContentStoreException extends RuntimeException {
    public ArtifactContentStoreException(String code) {
        super(code);
    }

    public ArtifactContentStoreException(String code, Throwable cause) {
        super(code, cause);
    }
}
