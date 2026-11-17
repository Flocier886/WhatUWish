package com.flocier.domain.agent.runtime.event;

/** RuntimeEvent v1 的受控 payload 标记接口。 */
public sealed interface RuntimeEventPayload permits RunStartedPayload, InputFilePayload, RunStatusPayload,
        LlmResponsePayload, ToolStartedPayload, ToolProgressPayload, ReportContentDeltaPayload, ToolResultPayload, ArtifactCreatedPayload,
        RunCompletedPayload, RunFailedPayload, DesktopControlPayload, RunWaitingUserInputPayload {
}
