package com.flocier.test.domain.skill;

import com.flocier.domain.agent.runtime.skill.SkillMarkdownParser;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SkillMarkdownParserTest {

    @Test
    public void shouldParseCommunityFrontMatterAsPrimaryMetadata() throws Exception {
        Path dir = Files.createTempDirectory("skill-md-parser-");
        Path skill = dir.resolve("SKILL.md");
        Files.writeString(skill, """
                ---
                name: community-demo
                description: "Demo skill from community package."
                version: 2.0.0
                ---

                # Community Demo

                Use this skill when the agent needs a lightweight community package.
                """, StandardCharsets.UTF_8);

        SkillMarkdownParser.ParsedSkillMarkdown parsed = new SkillMarkdownParser().parseWithFrontMatter(skill, 16000);

        Assert.assertEquals("community-demo", parsed.getFrontMatter().get("name"));
        Assert.assertEquals("Demo skill from community package.", parsed.getFrontMatter().get("description"));
        Assert.assertEquals("2.0.0", parsed.getFrontMatter().get("version"));
        Assert.assertTrue(parsed.getBody().contains("Community Demo"));
        Assert.assertTrue(parsed.getRawContent().contains("Use this skill"));
    }
}
