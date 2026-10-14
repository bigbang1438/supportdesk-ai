package com.supportdesk;

import jakarta.validation.constraints.*;
import java.util.List;

public final class AssistantModels {
    private AssistantModels() {}
    public record RunInput(@NotBlank @Size(max=1500) String question,
                           @NotNull @Pattern(regexp="demo|ollama") String mode) {}
    public record PublishInput(@NotNull @Min(0) Integer version) {}
    public record DiscardInput() {}
    public record Capabilities(boolean modelConfigured) {}
    public record Citation(long documentId, String filename, int lineStart, int lineEnd, String text, String chunkId) {}
    public record Trace(String tool, String status, String summary) {}
    public record RunView(long id, String question, String answer, String mode, List<Citation> citations,
                          List<Trace> steps, String reply, String state, int ticketVersion,
                          String createdAt, String expiresAt) {}
}
