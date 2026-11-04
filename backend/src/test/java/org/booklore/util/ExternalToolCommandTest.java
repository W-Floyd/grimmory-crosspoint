package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ExternalToolCommandTest {

    @Test
    void build_keepsToolPathAndSubstitutedPathsAsSingleTokens() {
        // A tool path AND a substituted value that both contain spaces must each stay one argv element.
        List<String> argv = ExternalToolCommand.build(
                "/opt/my tools/handler",
                "--acsm {acsm} --output {output}",
                Map.of("acsm", "/tmp/with space/f.acsm", "output", "/tmp/out dir/book.epub"));

        assertThat(argv).containsExactly(
                "/opt/my tools/handler",
                "--acsm", "/tmp/with space/f.acsm",
                "--output", "/tmp/out dir/book.epub");
    }

    @Test
    void build_honoursDoubleQuotedArgGroups() {
        List<String> argv = ExternalToolCommand.build(
                "tool",
                "--label \"hello world\" {input}",
                Map.of("input", "/x/y.json"));

        assertThat(argv).containsExactly("tool", "--label", "hello world", "/x/y.json");
    }

    @Test
    void build_handlesBlankArgs() {
        assertThat(ExternalToolCommand.build("tool", "", Map.of())).containsExactly("tool");
        assertThat(ExternalToolCommand.build("tool", null, Map.of())).containsExactly("tool");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void destroyTree_killsTheToolsChildrenToo() throws Exception {
        Process tool = new ProcessBuilder("sh", "-c", "sleep 60 & wait").start();
        await().atMost(Duration.ofSeconds(5)).until(() -> tool.descendants().findAny().isPresent());
        ProcessHandle child = tool.descendants().findFirst().orElseThrow();

        ExternalToolCommand.destroyTree(tool);

        await().atMost(Duration.ofSeconds(5)).until(() -> !tool.isAlive() && !child.isAlive());
    }
}
