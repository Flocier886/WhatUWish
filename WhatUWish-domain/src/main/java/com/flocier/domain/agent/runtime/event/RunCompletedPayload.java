package com.flocier.domain.agent.runtime.event;

import java.util.List;
import lombok.Getter;

/** Run 正常完成事件的受控展示数据。 */
@Getter
public final class RunCompletedPayload implements RuntimeEventPayload {

    private final String status;
    private final String summary;
    private final List<String> artifactIds;
    private final String artifactSelection;

    public RunCompletedPayload(String status, String summary, List<String> artifactIds, String artifactSelection) {
        this.status = status;
        this.summary = summary;
        this.artifactIds = artifactIds;
        this.artifactSelection = artifactSelection;
    }
}
