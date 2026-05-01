package com.ai.rag.util;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/**
 * Sanitizes user-supplied text before injection into LLM prompts.
 * Prevents prompt injection via crafted system-override strings.
 */
@Component
public class PromptSanitizer {

    /** Characters / phrases commonly used to escape prompt context. */
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(?i)(ignore (previous|above|all) instructions?|"
            + "system\\s*:|assistant\\s*:|\\[INST\\]|\\[/INST\\]|"
            + "<\\|im_start\\|>|<\\|im_end\\|>|"
            + "\\bDAN\\b|jailbreak)",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_LENGTH = 2000;

    public String sanitize(String input) {
        if (input == null) return "";
        String truncated = input.length() > MAX_LENGTH ? input.substring(0, MAX_LENGTH) : input;
        return INJECTION_PATTERN.matcher(truncated).replaceAll("[FILTERED]");
    }

    public String sanitizeContext(String context) {
        if (context == null) return "";
        // Context from client-supplied streaming requests gets aggressive sanitization
        String sanitized = sanitize(context);
        // Strip any XML-like tags that could confuse chat templates
        return sanitized.replaceAll("<[^>]{0,50}>", "");
    }
}
