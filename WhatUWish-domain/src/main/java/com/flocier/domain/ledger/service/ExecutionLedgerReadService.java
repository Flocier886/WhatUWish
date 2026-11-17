package com.flocier.domain.ledger.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.ILlmInvocationLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IRunLedgerRepository;
import com.flocier.domain.ledger.adapter.repository.IToolInvocationLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.ledger.model.entity.LlmInvocationLedgerEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.ToolInvocationLedgerEntity;
import com.flocier.domain.ledger.model.execution.ExecutionLedgerView;
import com.flocier.domain.ledger.model.execution.ExecutionLlmCycle;
import com.flocier.domain.ledger.model.execution.ExecutionRunView;
import com.flocier.domain.ledger.model.execution.ExecutionToolInvocation;
import com.flocier.domain.ledger.model.execution.ToolLlmAssociation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ledger 的批量结构读取入口。
 * 只负责读取、稳定排序和事实关联，禁止在此生成 prompt 或修改持久化记录。
 */
@Service
@RequiredArgsConstructor
public class ExecutionLedgerReadService {

    private final IRunLedgerRepository runLedgerRepository;
    private final ILlmInvocationLedgerRepository llmInvocationLedgerRepository;
    private final IToolInvocationLedgerRepository toolInvocationLedgerRepository;
    private final IArtifactLedgerRepository artifactLedgerRepository;

    public ExecutionLedgerView readSession(String sessionId, Integer limit) {
        List<RunLedgerEntity> runs = runLedgerRepository.findBySessionId(sessionId, limit).stream()
                .sorted(runComparator())
                .toList();
        return readRuns(sessionId, runs);
    }

    public ExecutionLedgerView readRun(String runId) {
        return runLedgerRepository.findByRunId(runId)
                .map(run -> readRuns(run.getSessionId(), List.of(run)))
                .orElseGet(() -> ExecutionLedgerView.builder().runs(List.of()).build());
    }

    public ExecutionLedgerView readRuns(String sessionId, List<RunLedgerEntity> requestedRuns) {
        List<RunLedgerEntity> runs = requestedRuns == null ? List.of() : requestedRuns.stream()
                .filter(item -> item != null && StringUtils.hasText(item.getRunId()))
                .sorted(runComparator())
                .toList();
        if (runs.isEmpty()) {
            return ExecutionLedgerView.builder().sessionId(sessionId).runs(List.of()).build();
        }
        List<String> runIds = runs.stream().map(RunLedgerEntity::getRunId).toList();
        Map<String, List<LlmInvocationLedgerEntity>> llms = llmInvocationLedgerRepository.findByRunIds(runIds).stream()
                .collect(Collectors.groupingBy(LlmInvocationLedgerEntity::getRunId));
        Map<String, List<ToolInvocationLedgerEntity>> tools = toolInvocationLedgerRepository.findByRunIds(runIds).stream()
                .collect(Collectors.groupingBy(ToolInvocationLedgerEntity::getRunId));
        Map<String, List<ArtifactLedgerEntity>> artifacts = artifactLedgerRepository.findByRunIdsIncludingDeleted(runIds).stream()
                .collect(Collectors.groupingBy(ArtifactLedgerEntity::getRunId));

        return ExecutionLedgerView.builder()
                .sessionId(sessionId)
                .runs(runs.stream().map(run -> assembleRun(
                        run,
                        llms.getOrDefault(run.getRunId(), List.of()),
                        tools.getOrDefault(run.getRunId(), List.of()),
                        artifacts.getOrDefault(run.getRunId(), List.of())))
                        .toList())
                .build();
    }

    private ExecutionRunView assembleRun(RunLedgerEntity run,
                                         List<LlmInvocationLedgerEntity> rawLlms,
                                         List<ToolInvocationLedgerEntity> rawTools,
                                         List<ArtifactLedgerEntity> rawArtifacts) {
        List<LlmInvocationLedgerEntity> llms = rawLlms.stream().sorted(llmComparator()).toList();
        List<ToolInvocationLedgerEntity> tools = rawTools.stream().sorted(toolComparator()).toList();
        List<ArtifactLedgerEntity> artifacts = rawArtifacts.stream().sorted(artifactComparator()).toList();
        Map<String, LlmInvocationLedgerEntity> llmById = llms.stream()
                .collect(Collectors.toMap(LlmInvocationLedgerEntity::getLlmInvocationId, item -> item));
        Map<String, List<LlmInvocationLedgerEntity>> llmsByDeclaredToolCall = indexDeclaredToolCalls(llms);
        Map<String, List<ArtifactLedgerEntity>> artifactsByToolInvocation = artifacts.stream()
                .filter(item -> StringUtils.hasText(item.getToolInvocationId()))
                .collect(Collectors.groupingBy(ArtifactLedgerEntity::getToolInvocationId));
        Map<String, List<ArtifactLedgerEntity>> artifactsByToolCall = artifacts.stream()
                .filter(item -> StringUtils.hasText(item.getToolCallId()))
                .collect(Collectors.groupingBy(ArtifactLedgerEntity::getToolCallId));
        Set<String> attachedArtifactIds = new HashSet<>();
        Map<String, List<ExecutionToolInvocation>> toolsByLlm = new HashMap<>();
        List<ExecutionToolInvocation> orphans = new ArrayList<>();

        for (ToolInvocationLedgerEntity tool : tools) {
            ToolLlmAssociation association = ToolLlmAssociation.ORPHAN;
            String parentId = null;
            if (StringUtils.hasText(tool.getLlmInvocationId()) && llmById.containsKey(tool.getLlmInvocationId())) {
                association = ToolLlmAssociation.PERSISTED;
                parentId = tool.getLlmInvocationId();
            } else {
                List<LlmInvocationLedgerEntity> candidates = llmsByDeclaredToolCall.getOrDefault(tool.getToolCallId(), List.of());
                if (candidates.size() == 1) {
                    association = ToolLlmAssociation.INFERRED;
                    parentId = candidates.get(0).getLlmInvocationId();
                }
            }
            List<ArtifactLedgerEntity> toolArtifacts = collectToolArtifacts(tool, artifactsByToolInvocation, artifactsByToolCall);
            toolArtifacts.forEach(item -> attachedArtifactIds.add(item.getArtifactId()));
            ExecutionToolInvocation view = ExecutionToolInvocation.builder()
                    .toolInvocation(tool)
                    .association(association)
                    .parentLlmInvocationId(parentId)
                    .artifacts(toolArtifacts)
                    .build();
            if (parentId == null) {
                orphans.add(view);
            } else {
                toolsByLlm.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(view);
            }
        }
        List<ExecutionLlmCycle> cycles = llms.stream().map(llm -> ExecutionLlmCycle.builder()
                .llmInvocation(llm)
                .toolInvocations(toolsByLlm.getOrDefault(llm.getLlmInvocationId(), List.of()).stream()
                        .sorted(executionToolComparator()).toList())
                .build()).toList();
        return ExecutionRunView.builder()
                .run(run)
                .llmCycles(cycles)
                .orphanToolInvocations(orphans.stream().sorted(executionToolComparator()).toList())
                .unassociatedArtifacts(artifacts.stream()
                        .filter(item -> !attachedArtifactIds.contains(item.getArtifactId()))
                        .toList())
                .build();
    }

    private Map<String, List<LlmInvocationLedgerEntity>> indexDeclaredToolCalls(List<LlmInvocationLedgerEntity> llms) {
        Map<String, List<LlmInvocationLedgerEntity>> index = new HashMap<>();
        for (LlmInvocationLedgerEntity llm : llms) {
            for (String toolCallId : declaredToolCallIds(llm)) {
                index.computeIfAbsent(toolCallId, ignored -> new ArrayList<>()).add(llm);
            }
        }
        return index;
    }

    private List<String> declaredToolCallIds(LlmInvocationLedgerEntity llm) {
        if (llm == null || !StringUtils.hasText(llm.getResponsePayloadJson())) {
            return List.of();
        }
        try {
            JSONObject response = JSON.parseObject(llm.getResponsePayloadJson());
            JSONObject metadata = response == null ? null : response.getJSONObject("metadata");
            JSONArray toolCalls = metadata == null ? null : metadata.getJSONArray("toolCalls");
            if (toolCalls == null) {
                return List.of();
            }
            return toolCalls.stream()
                    .filter(JSONObject.class::isInstance)
                    .map(JSONObject.class::cast)
                    .map(item -> item.getString("id"))
                    .filter(StringUtils::hasText)
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<ArtifactLedgerEntity> collectToolArtifacts(ToolInvocationLedgerEntity tool,
                                                             Map<String, List<ArtifactLedgerEntity>> byInvocation,
                                                             Map<String, List<ArtifactLedgerEntity>> byToolCall) {
        List<ArtifactLedgerEntity> result = new ArrayList<>(byInvocation.getOrDefault(tool.getToolInvocationId(), List.of()));
        if (result.isEmpty() && StringUtils.hasText(tool.getToolCallId())) {
            result.addAll(byToolCall.getOrDefault(tool.getToolCallId(), List.of()));
        }
        return result.stream().sorted(artifactComparator()).toList();
    }

    private Comparator<RunLedgerEntity> runComparator() {
        return Comparator.comparing(RunLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RunLedgerEntity::getRunId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<LlmInvocationLedgerEntity> llmComparator() {
        return Comparator.comparing(LlmInvocationLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LlmInvocationLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LlmInvocationLedgerEntity::getLlmInvocationId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<ToolInvocationLedgerEntity> toolComparator() {
        return Comparator.comparing(ToolInvocationLedgerEntity::getDispatchOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ToolInvocationLedgerEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ToolInvocationLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ToolInvocationLedgerEntity::getToolInvocationId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<ExecutionToolInvocation> executionToolComparator() {
        return Comparator.comparing((ExecutionToolInvocation item) -> item.getToolInvocation().getDispatchOrder(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.getToolInvocation().getStartedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.getToolInvocation().getToolInvocationId(), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<ArtifactLedgerEntity> artifactComparator() {
        return Comparator.comparing(ArtifactLedgerEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ArtifactLedgerEntity::getArtifactId, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
