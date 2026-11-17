package com.flocier.application.agent.imagegeneration.workbench;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 工作台一次提交的非敏感生成参数和暂存上传内容。 */
@Value
@Builder
public class WorkbenchImageGenerationCommand {
    String sessionId;
    String prompt;
    String mode;
    String outputFileName;
    String size;
    Integer count;
    List<WorkbenchImageInput> references;
    List<WorkbenchImageInput> masks;
}
