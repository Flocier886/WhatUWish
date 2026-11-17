package com.flocier.domain.agent.runtime.agent.tool.docgen;

import java.util.List;

/** 生成器定义直接取自各独立工具契约。 */
public final class DocGenContracts {
    private DocGenContracts() { }

    public static List<AbstractDocGenTool> all() {
        return List.of(new DocumentGenerateTool(), new SlidesGenerateTool(), new ExcelGeneratorTool(),
                new ChartGeneratorTool(), new ChecklistGenerateTool(), new TemplateFillerTool(),
                new DocumentTemplateTool(), new ThemeDesignerTool());
    }
}
