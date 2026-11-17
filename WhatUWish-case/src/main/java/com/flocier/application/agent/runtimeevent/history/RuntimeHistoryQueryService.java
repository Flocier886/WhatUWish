package com.flocier.application.agent.runtimeevent.history;

import com.flocier.application.agent.runtimeevent.history.dto.RuntimeHistoryEventDTO;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeRunReplayDTO;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeSessionHistoryDTO;
import com.flocier.application.agent.runtimeevent.history.dto.RuntimeSessionSummaryDTO;
import com.flocier.domain.agent.runtime.event.RuntimeEvent;
import com.flocier.domain.agent.runtime.event.DesktopControlPayload;
import com.flocier.domain.agent.runtime.desktopcontrol.DesktopControlRepository;
import com.flocier.domain.agent.runtime.event.InputFilePayload;
import com.flocier.domain.agent.runtime.event.RuntimeEventType;
import com.flocier.domain.ledger.adapter.repository.IArtifactLedgerRepository;
import com.flocier.domain.ledger.model.entity.ArtifactLedgerEntity;
import com.flocier.domain.agent.runtime.file.ConversationFileRepository;
import com.flocier.domain.agent.runtime.file.ConversationFileRecord;
import com.flocier.domain.agent.runtime.event.RuntimeReplayEventMapper;
import com.flocier.domain.agent.runtime.event.RuntimeReplayProjection;
import com.flocier.domain.agent.runtime.event.RuntimeReplayAssembler;
import com.flocier.domain.agent.runtime.event.RuntimeEventToolPresentation;
import com.flocier.domain.ledger.service.replay.rich.RichRuntimeReplayProjectorRegistry;
import com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity;
import com.flocier.domain.ledger.model.entity.LedgerSessionQueryEntity;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.entity.SessionLedgerEntity;
import com.flocier.domain.ledger.model.replay.RunReplayTimeline;
import com.flocier.domain.ledger.service.ExecutionReplayService;
import com.flocier.domain.ledger.service.LedgerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 本地单用户历史读取入口；未来身份范围校验应收口在该服务。 */
@Service
@RequiredArgsConstructor
public class RuntimeHistoryQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final LedgerQueryService ledgerQueryService;
    private final ExecutionReplayService executionReplayService;
    private final RuntimeReplayEventMapper replayEventMapper;
    private final RichRuntimeReplayProjectorRegistry richRuntimeReplayProjectorRegistry;
    private final RuntimeReplayAssembler runtimeReplayAssembler;
    private final IArtifactLedgerRepository artifactLedgerRepository;
    private final ConversationFileRepository conversationFileRepository;
    @Autowired(required = false)
    private DesktopControlRepository desktopControlRepository;

    /** 当前本地单用户运行时按账本列出所有会话。 */
    public List<RuntimeSessionSummaryDTO> listSessions(Integer limit) {
        return ledgerQueryService.queryRecentSessions(LedgerSessionQueryEntity.builder().limit(normalizeLimit(limit)).build())
                .stream()
                .map(this::toSession).toList();
    }

    public Optional<RuntimeSessionHistoryDTO> findSession(String sessionId, Integer limit) {
        return findSession(sessionId, limit, false);
    }

    public Optional<RuntimeSessionHistoryDTO> findSession(String sessionId, Integer limit, boolean includeInputs) {
        if (!StringUtils.hasText(sessionId)) return Optional.empty();
        Optional<SessionLedgerEntity> session = ledgerQueryService.querySession(sessionId);
        if (session.isEmpty()) return Optional.empty();
        List<RunLedgerEntity> runs = ledgerQueryService.queryRuns(LedgerRunQueryEntity.builder()
                .sessionId(sessionId).limit(normalizeLimit(limit)).build());
        Map<String, RunReplayTimeline> replayByRunId = executionReplayService.replaySession(sessionId, normalizeLimit(limit)).stream()
                .collect(Collectors.toMap(RunReplayTimeline::getRunId, Function.identity(), (left, right) -> left));
        List<RuntimeRunReplayDTO> replays = runs.stream()
                .map(run -> toRun(run, replayByRunId.get(run.getRunId()), includeInputs))
                .toList();
        return Optional.of(new RuntimeSessionHistoryDTO(toSession(session.get()), replays, true));
    }

    public Optional<RuntimeRunReplayDTO> findRun(String runId) {
        return findRun(runId, false);
    }

    public Optional<RuntimeRunReplayDTO> findRun(String runId, boolean includeInputs) {
        if (!StringUtils.hasText(runId)) return Optional.empty();
        return ledgerQueryService.queryRunView(runId)
                .map(view -> toRun(view.getRun(), executionReplayService.replayRun(runId), includeInputs));
    }

    private RuntimeRunReplayDTO toRun(RunLedgerEntity run, RunReplayTimeline replay, boolean includeInputs) {
        RuntimeReplayProjection projection = runtimeReplayAssembler.assemble(
                run,
                replayEventMapper.project(run, replay),
                richRuntimeReplayProjectorRegistry.project(run)
        );
        String task = queryText(run);
        boolean dataIncomplete = projection.isDataIncomplete() || !StringUtils.hasText(task);
        List<RuntimeEvent> events = new ArrayList<>(projection.getEvents());
        if (desktopControlRepository != null) {
            var controls = desktopControlRepository.listBySessionId(run.getSessionId()).stream()
                    .filter(item -> run.getRunId().equals(item.getSourceRunId())).toList();
            if (!controls.isEmpty()) {
                List<RuntimeEvent> combined = new ArrayList<>();
                long sequence = 0;
                for (RuntimeEvent event : events) {
                    if (event.getEventType() == RuntimeEventType.RUN_WAITING_USER_INPUT) {
                        for (var control : controls) {
                            combined.add(new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1,
                                    "history:" + run.getRunId() + ":desktop:" + control.getControlId(), ++sequence,
                                    RuntimeEventType.DESKTOP_CONTROL, run.getFinishedAt(), run.getSessionId(),
                                    run.getRunId(), run.getRequestId(), run.getAgentId(),
                                    DesktopControlPayload.replay(control)));
                        }
                    }
                    combined.add(new RuntimeEvent(event.getSchemaVersion(), event.getEventId(), ++sequence,
                            event.getEventType(), event.getOccurredAt(), event.getSessionId(), event.getRunId(),
                            event.getRequestId(), event.getAgentId(), event.getPayload()));
                }
                events = combined;
            }
        }
        // 输入事件只能在控制器通过文件令牌校验后投影；状态按当前会话文件记录重新计算。
        if (includeInputs) {
            List<ArtifactLedgerEntity> inputs = artifactLedgerRepository.findByRunId(run.getRunId()).stream()
                    .filter(item -> "input".equalsIgnoreCase(item.getArtifactRole())
                            && "user_upload".equals(item.getSourceType()))
                    .sorted(Comparator.comparingInt(this::inputOrder)).toList();
            long sequence = 0;
            List<RuntimeEvent> combined = new ArrayList<>();
            for (ArtifactLedgerEntity input : inputs) {
                ConversationFileRecord file = conversationFileRepository.findFile(input.getSourceId()).orElse(null);
                String status = file != null && file.ready() ? "AVAILABLE" : "UNAVAILABLE";
                combined.add(new RuntimeEvent(RuntimeEvent.SCHEMA_VERSION_V1,
                        "history:" + run.getRunId() + ":input:" + input.getArtifactId(), ++sequence,
                        RuntimeEventType.INPUT_FILE, input.getCreatedAt(), run.getSessionId(), run.getRunId(),
                        run.getRequestId(), run.getAgentId(), new InputFilePayload(input.getSourceId(),
                        input.getFileName(), input.getMimeType(), input.getFileSize() == null ? 0 : input.getFileSize(),
                        inputOrder(input), status)));
            }
            for (RuntimeEvent event : events) combined.add(new RuntimeEvent(event.getSchemaVersion(), event.getEventId(),
                    ++sequence, event.getEventType(), event.getOccurredAt(), event.getSessionId(), event.getRunId(),
                    event.getRequestId(), event.getAgentId(), event.getPayload()));
            events = combined;
        }
        return new RuntimeRunReplayDTO(run.getRunId(), run.getSessionId(), run.getRequestId(), run.getAgentId(),
                task, normalizeRunStatus(run.getStatus()), run.getStartedAt(), run.getFinishedAt(),
                RuntimeEventToolPresentation.summary(run.getSummary()), run.getErrorCode(),
                RuntimeEventToolPresentation.errorMessage(run.getErrorMessage()), true, dataIncomplete,
                events.stream().map(this::toEvent).toList());
    }

    private int inputOrder(ArtifactLedgerEntity artifact) {
        try {
            return com.alibaba.fastjson.JSON.parseObject(artifact.getMetadataJson()).getIntValue("inputOrder");
        } catch (RuntimeException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private RuntimeHistoryEventDTO toEvent(RuntimeEvent event) {
        return new RuntimeHistoryEventDTO(event.getSchemaVersion(), event.getEventId(), event.getSequence(), event.getEventType().name(),
                event.getOccurredAt(), event.getSessionId(), event.getRunId(), event.getRequestId(), event.getAgentId(), event.getPayload());
    }

    private RuntimeSessionSummaryDTO toSession(SessionLedgerEntity session) {
        String title = StringUtils.hasText(session.getTitle()) ? session.getTitle()
                : StringUtils.hasText(session.getLatestQueryText()) ? abbreviate(session.getLatestQueryText()) : "新建对话";
        return new RuntimeSessionSummaryDTO(session.getSessionId(), title, session.getAgentId(), session.getStatus(),
                session.getLatestQueryText(), session.getLatestSummaryText(), intValue(session.getRunCount()),
                intValue(session.getFinishedRunCount()), intValue(session.getFailedRunCount()), session.getStartedAt(),
                session.getLastActiveAt(), true);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private int intValue(Integer value) { return value == null ? 0 : value; }

    private String normalizeRunStatus(String status) {
        if ("FAILED".equalsIgnoreCase(status)) return "FAILED";
        if ("WAITING_USER_INPUT".equalsIgnoreCase(status)) return "WAITING_USER_INPUT";
        if ("COMPLETED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status)) return "COMPLETED";
        return "RUNNING";
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** query_text is authoritative; ext.queryText exists only for pre-upgrade rows. */
    private String queryText(RunLedgerEntity run) {
        if (run == null) return "";
        if (StringUtils.hasText(run.getQueryText())) return run.getQueryText();
        return text(run.getExt(), "queryText");
    }

    private String abbreviate(String value) {
        return value.length() <= 22 ? value : value.substring(0, 22);
    }
}
