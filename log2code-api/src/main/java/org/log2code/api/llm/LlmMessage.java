package org.log2code.api.llm;

public record LlmMessage(Role role, String text) {

    public enum Role {
        USER,
        MODEL
    }
}
