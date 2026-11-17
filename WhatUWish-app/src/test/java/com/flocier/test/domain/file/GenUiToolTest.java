package com.flocier.test.domain.file;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.genui.EmitUiPatchTool;
import com.flocier.domain.agent.runtime.agent.tool.genui.EmitUiTreeTool;
import com.flocier.domain.agent.runtime.agent.tool.genui.GenUiPresentation;
import com.flocier.domain.agent.runtime.agent.tool.genui.GetGenuiGuideTool;
import com.flocier.domain.agent.runtime.agent.tool.genui.ListUiComponentsTool;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class GenUiToolTest {
    @Test public void guideCatalogTreeAndPatchUseSameContract() {
        var guide = (ToolResultPayload) new GetGenuiGuideTool().execute(null, null);
        var catalog = (ToolResultPayload) new ListUiComponentsTool().execute(null, null);
        assertTrue(guide.isSuccess());
        assertTrue(catalog.isSuccess());
        assertTrue(catalog.getLlmObservation().contains("ThreeJsFrame"));
        assertTrue(catalog.getLlmObservation().contains("ConceptDemo"));
        var root = Map.of("kind", "SectionHeader", "title", "中文内容");
        var tool = new EmitUiTreeTool();
        var object = (ToolResultPayload) tool.execute(request(Map.of("tree", root, "canvas_id", "canvas-a")), null);
        var string = (ToolResultPayload) tool.execute(request(Map.of("tree", JSON.toJSONString(root))), null);
        assertTrue(object.isSuccess()); assertTrue(string.isSuccess());
        assertEquals("genui.tree.v1", GenUiPresentation.fromStructured("emit_ui_tree", object.getStructuredOutput()).get("kind"));
        assertNotNull(GenUiPresentation.fromLedger("emit_ui_tree", JSON.toJSONString(Map.of(
                "success", true, "result", object.getStructuredOutput()))));
        var patch = (ToolResultPayload) new EmitUiPatchTool().execute(request(Map.of("canvas_id", "canvas-a",
                "patches", List.of(Map.of("op", "replace", "path", "/root/props/value", "value", "更新")))), null);
        assertTrue(patch.isSuccess());
        assertEquals("genui.patch.v1", GenUiPresentation.fromStructured("emit_ui_patch", patch.getStructuredOutput()).get("kind"));
    }

    @Test public void invalidTreesAndPatchesDoNotPublish() {
        var tool = new EmitUiTreeTool();
        assertFalse(((ToolResultPayload) tool.execute(request(Map.of("tree", "not json")), null)).isSuccess());
        assertFalse(((ToolResultPayload) tool.execute(request(Map.of("tree", Map.of("kind", "Nonexistent"))), null)).isSuccess());
        Map<String, Object> deep = Map.of("kind", "SectionHeader", "title", "leaf");
        for (int i = 0; i < 25; i++) deep = Map.of("kind", "Card", "children", List.of(deep));
        assertFalse(((ToolResultPayload) tool.execute(request(Map.of("tree", deep)), null)).isSuccess());
        var many = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 201; i++) many.add(Map.of("kind", "SectionHeader", "title", "item" + i));
        assertFalse(((ToolResultPayload) tool.execute(request(Map.of("tree", Map.of(
                "kind", "Card", "children", many))), null)).isSuccess());
        assertFalse(((ToolResultPayload) new EmitUiPatchTool().execute(request(Map.of("patches",
                List.of(Map.of("op", "move", "path", "/root")))), null)).isSuccess());
        assertNull(GenUiPresentation.fromLedger("emit_ui_tree", "{\"success\":false}"));
    }

    private ToolInvokeRequest request(Map<String, Object> args) {
        return ToolInvokeRequest.builder().arguments(args).build();
    }
}
