package com.flocier.domain.agent.runtime.event;

/** 面向运行展示消费者的稳定事件类别。 */
public enum RuntimeEventType {
    RUN_STARTED,
    INPUT_FILE,
    RUN_STATUS,
    LLM_RESPONSE,
    TOOL_STARTED,
    TOOL_PROGRESS,
    /** 仅当前 Live Run 可见的 Report 正文增量；不属于可回放账本事实。 */
    REPORT_CONTENT_DELTA,
    TOOL_RESULT,
    ARTIFACT_CREATED,
    DESKTOP_CONTROL,
    RUN_WAITING_USER_INPUT,
    RUN_COMPLETED,
    RUN_FAILED
}
