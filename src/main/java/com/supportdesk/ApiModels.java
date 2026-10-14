package com.supportdesk;

import jakarta.validation.constraints.*;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}
    public record Actor(long userId, long tenantId, String username, String displayName, String role, String workspaceName) {}
    public record OrderView(long id, String number, String itemName, int quantity, int totalCents, String status, String updatedAt) {}
    public record TicketView(long id, String number, String subject, String body, long orderId, String orderNumber, String customerName, String status, int version, String createdAt, String updatedAt) {}
    public record MessageView(long id, long ticketId, String author, String role, String body, String createdAt) {}
    public record PolicyView(long id, String title, String filename, String content, int version, String updatedAt) {}
    public record MessageInput(@NotBlank @Size(max=1500) String body, @NotNull @Min(0) Integer version) {}
    public record Meta(int page, int size, long total, int totalPages) {}
    public record Page<T>(List<T> data, Meta meta) {}
    public record TicketInput(@NotNull @Positive Long orderId, @NotBlank @Size(max=100) String subject, @NotBlank @Size(max=2000) String body) {}
    public record StatusInput(@NotBlank String status, @NotNull @Min(0) Integer version) {}
    public record PolicyInput(@NotBlank @Size(max=100) String title, @NotBlank @Size(max=80) String filename, @NotBlank @Size(max=4000) String content) {}
    public record PolicyUpdateInput(@NotBlank @Size(max=100) String title, @NotBlank @Size(max=80) String filename, @NotBlank @Size(max=4000) String content, @NotNull @Min(0) Integer version) {}
    public record EventView(long id, String action, String actor, String detail, String createdAt) {}
    public record Summary(long total, long open, long processing, long resolved) {}
}
