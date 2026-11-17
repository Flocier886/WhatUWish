package com.flocier.domain.agent.runtime.memory.review;

/**
 * 原文来自 master-02 LtmPromptGuidance.REVIEW_SKILL_GUIDANCE / REVIEW_DIRECTIVE。
 * 映射本项目后台 review 允许的 skill 与 workspace 工具；不执行脚本。
 * PRIORITY/STYLE/SKIP 是偏好条目的规则，其中 SKIP 禁止完整流程，与本阶段 Skill 目标冲突，故不搬入。
 */
public final class SkillReviewPrompt {
    public static final String SKILL_GUIDANCE =
            "Also update the skill library when warranted. Be ACTIVE — reusable workflows belong in "
                    + "skills, not memory.\n"
                    + "How (this runtime):\n"
                    + "  • Inspect with skill_view / skills_search / workspace_list|glob|grep|read under skills/\n"
                    + "  • Create or patch via workspace_write|edit on skills/<name>/SKILL.md "
                    + "(and scripts/, references/, templates/ as needed)\n"
                    + "Prefer: (1) patch a skill already used this session, (2) extend an existing "
                    + "class-level skill, (3) add support files, (4) create a new class-level skill.\n"
                    + "Skill names must be class-level — not one-off PR/error/session artifacts.\n"
                    + "Do NOT put task progress or ephemeral TODOs into skills; those stay out of memory too.";

    public static final String DIRECTIVE =
            "Review the conversation above and improve skills if appropriate.\n\n"
                    + SKILL_GUIDANCE + "\n\n"
                    + "You may only call curator tools that succeed at runtime: skill_view, skills_search, "
                    + "workspace_list, workspace_glob, workspace_grep, workspace_read, workspace_write, workspace_edit. "
                    + "Other tools remain listed for request parity but will be denied — do not attempt them.\n"
                    + "This is background skill maintenance. The conversation above is review material; "
                    + "do not resume the user's task or deliver a user-facing answer. "
                    + "Do not save user persona/preferences, raw session logs, secrets, or unresolved failures. "
                    + "Use a class-level package name and YAML frontmatter with name and description in SKILL.md. "
                    + "Read existing files with workspace_read before modifying; edits require exact text without line-number prefixes. "
                    + "You may write script files, but cannot execute scripts in this review.\n"
                    + "After maintenance, return a brief, non-empty internal completion summary; "
                    + "this is an audit result, not a user-facing answer. Do not end with empty content.\n"
                    + "If something stands out, save it (skills). "
                    + "If nothing new is worth saving, say 'Nothing to save.' and stop without tool calls.";
    private SkillReviewPrompt() { }
}
