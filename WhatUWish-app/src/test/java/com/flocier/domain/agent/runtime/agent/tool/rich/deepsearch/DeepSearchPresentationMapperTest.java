package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** DeepSearch 展示投影的字段、限长和脱敏边界测试。 */
public class DeepSearchPresentationMapperTest {

    private final DeepSearchPresentationMapper mapper = new DeepSearchPresentationMapper();

    @Test
    public void shouldMapSourceSelectionAndFetchFailureWithoutContent() {
        DeepSearchStreamEvent selection = event(
                "source_selection_completed",
                Map.of(
                        "kind", "deep_search.source_selection.v1",
                        "schemaVersion", 1,
                        "data", Map.of(
                                "iteration", 1,
                                "usedFallback", true,
                                "sources", List.of(source("selected", "selection reason"))
                        )
                )
        );
        DeepSearchStreamEvent fetched = event(
                "fetch_completed",
                Map.of(
                        "kind", "deep_search.fetch_results.v1",
                        "schemaVersion", 1,
                        "data", Map.of(
                                "iteration", 1,
                                "sources", List.of(Map.ofEntries(
                                        Map.entry("sourceId", "source-1"),
                                        Map.entry("title", "Source title"),
                                        Map.entry("url", "https://example.com/source"),
                                        Map.entry("domain", "example.com"),
                                        Map.entry("provider", "tavily"),
                                        Map.entry("status", "fetch_failed"),
                                        Map.entry("contentLength", 0),
                                        Map.entry("truncated", false),
                                        Map.entry("errorCode", "fetch_timeout"),
                                        Map.entry("content", "must-not-store")
                                ))
                        )
                )
        );

        DeepSearchPresentationMapper.DeepSearchPresentation selected = mapper.map(selection);
        DeepSearchPresentationMapper.DeepSearchPresentation fetchResult = mapper.map(fetched);
        Map<?, ?> selectedSource = (Map<?, ?>) ((List<?>) selected.data().get("sources")).get(0);

        Assert.assertEquals("deep_search.source_selection.v1", selected.kind());
        Assert.assertEquals("selection reason", selectedSource.get("reason"));
        Assert.assertEquals("fetch_timeout", ((Map<?, ?>) ((List<?>) fetchResult.data().get("sources")).get(0)).get("errorCode"));
        Assert.assertFalse(fetchResult.data().toString().contains("must-not-store"));
        Assert.assertFalse(fetchResult.data().toString().contains("content="));
    }

    @Test
    public void shouldBoundEvidenceAndExcludeArtifactStorageFields() {
        String quote = "q".repeat(500);
        DeepSearchStreamEvent evidence = event(
                "evidence_verified",
                Map.of(
                        "kind", "deep_search.evidence_summary.v1",
                        "schemaVersion", 1,
                        "data", Map.of(
                                "iteration", 1,
                                "newEvidenceCount", 1,
                                "evidenceCount", 1,
                                "evidence", List.of(Map.of(
                                        "sourceId", "source-1",
                                        "title", "Evidence source",
                                        "url", "https://example.com/evidence",
                                        "claim", "A verified claim",
                                        "quotePreview", quote,
                                        "verification", "verified",
                                        "rawResponse", "must-not-store"
                                ))
                        )
                )
        );
        DeepSearchStreamEvent artifact = event(
                "artifact_created",
                Map.of(
                        "kind", "deep_search.artifact.v1",
                        "schemaVersion", 1,
                        "data", Map.of(
                                "artifactId", "artifact-1",
                                "name", "Evidence package",
                                "artifactType", "research_evidence_package",
                                "mimeType", "text/markdown",
                                "summary", "safe summary",
                                "fileSize", 42,
                                "storageRef", "must-not-store"
                        )
                )
        );

        DeepSearchPresentationMapper.DeepSearchPresentation evidenceResult = mapper.map(evidence);
        DeepSearchPresentationMapper.DeepSearchPresentation artifactResult = mapper.map(artifact);
        Map<?, ?> evidenceItem = (Map<?, ?>) ((List<?>) evidenceResult.data().get("evidence")).get(0);

        Assert.assertEquals(240, ((String) evidenceItem.get("quotePreview")).length());
        Assert.assertFalse(evidenceResult.data().toString().contains("rawResponse"));
        Assert.assertFalse(artifactResult.data().toString().contains("storageRef"));
        Assert.assertEquals("artifact-1", artifactResult.data().get("artifactId"));
    }

    private DeepSearchStreamEvent event(String phase, Map<String, Object> presentation) {
        DeepSearchStreamEvent event = new DeepSearchStreamEvent();
        event.setSchemaVersion("deep_search_event.v1");
        event.setEventId("evt-1");
        event.setExecutionId("python-1");
        event.setSequence(1);
        event.setPhase(phase);
        event.setStatus("running");
        event.setOccurredAt(Instant.parse("2026-08-05T08:00:00Z"));
        event.setSummary("test stage");
        event.setPresentation(presentation);
        return event;
    }

    private Map<String, Object> source(String status, String reason) {
        return Map.of(
                "sourceId", "source-1",
                "title", "Source title",
                "url", "https://example.com/source",
                "domain", "example.com",
                "provider", "tavily",
                "status", status,
                "reason", reason
        );
    }
}
