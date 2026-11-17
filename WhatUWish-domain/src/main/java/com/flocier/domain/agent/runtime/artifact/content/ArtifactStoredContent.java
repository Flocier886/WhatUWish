package com.flocier.domain.agent.runtime.artifact.content;

import lombok.Value;

/** 存储层可安全返回的内容事实。 */
@Value
public class ArtifactStoredContent {
    long size;
}
