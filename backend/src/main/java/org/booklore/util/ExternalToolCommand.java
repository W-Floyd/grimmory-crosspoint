package org.booklore.util;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link ProcessBuilder} argv for an operator-configured external tool
 * ({@code tool-path} + {@code tool-args}) used by the OverDrive ACSM / audiobook / magazine handlers.
 *
 * <p>The command is assembled as a proper argv list — never a shell string — so shell metacharacters
 * are inert and no shell is spawned. Crucially, {@code toolPath} is a single argv element and the
 * {@code toolArgs} template is tokenized on whitespace <em>with the placeholders still in place</em>,
 * then each token has its placeholders substituted. That keeps a substituted value that contains
 * spaces (e.g. a temp path under a home directory with a space in it) as one argument instead of
 * splitting it apart — the bug a naive {@code (toolPath + " " + args).split("\\s+")} has. Simple
 * double-quoted groups in the template are honoured so an operator can pass an argument that
 * legitimately contains spaces.
 */
@UtilityClass
public class ExternalToolCommand {

    /**
     * @param toolPath      the tool executable path (kept as a single argv element, spaces and all)
     * @param argsTemplate  the arg template with {@code {placeholder}} tokens (may be null/blank)
     * @param placeholders  placeholder name → replacement value (substituted per-token, post-tokenize)
     * @return the argv list to hand to {@link ProcessBuilder}
     */
    public List<String> build(String toolPath, String argsTemplate, Map<String, String> placeholders) {
        List<String> argv = new ArrayList<>();
        argv.add(toolPath);
        for (String token : tokenize(argsTemplate)) {
            argv.add(substitute(token, placeholders));
        }
        return argv;
    }

    private String substitute(String token, Map<String, String> placeholders) {
        String result = token;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    /** Split on unquoted whitespace; strip surrounding double quotes from each token. */
    private List<String> tokenize(String template) {
        List<String> tokens = new ArrayList<>();
        if (template == null || template.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean have = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                have = true;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (have) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    have = false;
                }
            } else {
                current.append(c);
                have = true;
            }
        }
        if (have) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
