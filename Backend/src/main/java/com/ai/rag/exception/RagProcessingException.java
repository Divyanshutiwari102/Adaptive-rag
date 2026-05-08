package com.ai.rag.exception;
public class RagProcessingException extends RuntimeException {
    public RagProcessingException(String msg) { super(msg); }
    public RagProcessingException(String msg, Throwable cause) { super(msg, cause); }
}
