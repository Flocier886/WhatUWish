package com.flocier.domain.agent.runtime.artifact;

import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** 历史运行回放时的文本与可交付文件结果。 */
@Value
@Builder
public class ArtifactReplayResult {
    String summaryText;
    List<ArtifactLedgerEntity> fileList;
    List<String> artifactRefs;
    boolean explicitSelection;
}
