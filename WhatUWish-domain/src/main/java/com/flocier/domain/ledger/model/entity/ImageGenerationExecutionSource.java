package com.flocier.domain.ledger.model.entity;

import java.util.Set;

/** 图片生成业务执行的稳定入口来源。 */
public final class ImageGenerationExecutionSource {
    public static final String AGENT_RUNTIME = "AGENT_RUNTIME";
    public static final String WORKBENCH = "WORKBENCH";

    private static final Set<String> VALUES = Set.of(AGENT_RUNTIME, WORKBENCH);

    private ImageGenerationExecutionSource() { }

    public static boolean isSupported(String value) {
        return VALUES.contains(value);
    }
}
