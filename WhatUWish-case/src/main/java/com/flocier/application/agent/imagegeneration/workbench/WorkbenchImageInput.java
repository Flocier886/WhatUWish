package com.flocier.application.agent.imagegeneration.workbench;

import lombok.Builder;
import lombok.Value;

/** 浏览器上传后进入应用层的受限图片内容；不会成为持久前端状态或 Runtime Event。 */
@Value
@Builder
public class WorkbenchImageInput {
    String fileName;
    String declaredMimeType;
    byte[] content;
}
