package com.flocier.domain.agent.runtime.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 注册中心。
 */
public interface SkillRegistry {

    void refresh();

    List<SkillDefinition> listSkills();

    Optional<SkillDefinition> findSkill(String skillName);

    SkillDefinition getRequiredSkill(String skillName);

    SkillScriptDefinition getRequiredScript(String skillName, String scriptName);

    Path assertPathAllowed(Path path);

    String buildSkillDescription();

    List<String> listPackageSources();

    List<Map<String, Object>> listValidationReports();
}
