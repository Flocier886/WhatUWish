package com.flocier.domain.agent.runtime.skill;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Skill 定义解析器。
 */
public interface SkillParser {

    SkillDefinition parse(Path skillDirectory, SkillRuntimeOptions options) throws IOException;
}
