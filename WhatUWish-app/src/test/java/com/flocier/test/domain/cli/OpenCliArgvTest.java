package com.flocier.test.domain.cli;

import com.flocier.domain.agent.runtime.agent.tool.cli.OpenCliArgv;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class OpenCliArgvTest {
    @Test
    public void appendsJsonFormatOnlyForSiteCommands() {
        assertEquals(List.of("hackernews", "top", "-f", "json"),
                OpenCliArgv.rewrite(List.of("hackernews", "top"), List.of("-f", "json")));
        assertEquals(List.of("browser", "tabs"),
                OpenCliArgv.rewrite(List.of("browser", "tabs"), List.of("-f", "json")));
        assertEquals(List.of("hackernews", "top", "--format=json"),
                OpenCliArgv.rewrite(List.of("hackernews", "top", "--format=json"), List.of("-f", "json")));
        assertEquals(List.of("--help"),
                OpenCliArgv.rewrite(List.of("--help"), List.of("-f", "json")));
        assertEquals(List.of("twitter", "--help"),
                OpenCliArgv.rewrite(List.of("twitter", "--help"), List.of("-f", "json")));
        assertEquals(List.of("twitter", "search", "--help", "-f", "yaml"),
                OpenCliArgv.rewrite(List.of("twitter", "search", "--help", "-f", "yaml"), List.of("-f", "json")));
    }

    @Test
    public void detectsSensitiveBrowserCommands() {
        assertTrue(OpenCliArgv.isSensitive(List.of("browser", "cookies")));
        assertTrue(OpenCliArgv.isSensitive(List.of("browser", "eval", "document.cookie")));
        assertEquals("eval", OpenCliArgv.sensitiveKind(List.of("browser", "eval")));
        assertFalse(OpenCliArgv.isSensitive(List.of("hackernews", "search", "java")));
    }
}
