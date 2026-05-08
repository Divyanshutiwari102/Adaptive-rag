package com.ai.rag.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class QueryComplexityAnalyzer {

    public enum SearchStrategy { GENERAL_LLM, KEYWORD_SEARCH, VECTOR_SEARCH }

    @Value("${rag.simple-query-max-words:5}")
    private int simpleQueryMaxWords;

    private static final Set<String> CHITCHAT = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay",
            "bye", "goodbye", "help", "what can you do");

    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
            "\\b(define|what is|who is|when did|where is|list|show me all)\\b",
            Pattern.CASE_INSENSITIVE);

    public SearchStrategy analyze(String query) {
        String q = query.strip().toLowerCase();
        if (CHITCHAT.contains(q)) return SearchStrategy.GENERAL_LLM;
        int wordCount = q.split("\\s+").length;
        if (wordCount <= simpleQueryMaxWords && KEYWORD_PATTERN.matcher(q).find())
            return SearchStrategy.KEYWORD_SEARCH;
        return SearchStrategy.VECTOR_SEARCH;
    }
}
