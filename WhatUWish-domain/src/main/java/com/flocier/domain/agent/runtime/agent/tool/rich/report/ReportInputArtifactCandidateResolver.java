package com.flocier.domain.agent.runtime.agent.tool.rich.report;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.artifact.ArtifactRuntimeBinding;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Report 对当前 Run 调研材料的运行期候选发现。
 *
 * <p>这里只提供稳定 Artifact ID，不承担存储读取或授权；最终准入仍由
 * {@link ReportInputArtifactResolver} 从 Artifact Ledger 完成。</p>
 */
@Service
public class ReportInputArtifactCandidateResolver {

    private static final String DEEP_SEARCH_TOOL = "deep_search_tool";
    private static final String RESEARCH_SOURCE_PACKAGE = "research_source_package";

    public List<String> resolveCurrentResearchArtifactIds(AgentContext context) {
        if (context == null || context.getArtifactRuntimeRegistry() == null) {
            return List.of();
        }
        Object runIdAttribute = context.getAttribute("runId");
        String runId = runIdAttribute == null ? null : String.valueOf(runIdAttribute);
        if (!StringUtils.hasText(runId)) {
            return List.of();
        }
        return context.getArtifactRuntimeRegistry().listDeliverableOutputs().stream()
                .filter(binding -> isResearchOutput(binding, runId))
                .map(ArtifactRuntimeBinding::getDescriptor)
                .map(descriptor -> descriptor == null ? null : descriptor.getArtifactId())
                .filter(StringUtils::hasText)
                .distinct()
                .limit(12)
                .toList();
    }

    private boolean isResearchOutput(ArtifactRuntimeBinding binding, String runId) {
        if (binding == null || binding.getSource() == null || binding.getDescriptor() == null) {
            return false;
        }
        return runId.equals(binding.getSource().getRunId())
                && DEEP_SEARCH_TOOL.equals(binding.getSource().getToolName())
                && RESEARCH_SOURCE_PACKAGE.equals(binding.getDescriptor().getArtifactType());
    }
}
