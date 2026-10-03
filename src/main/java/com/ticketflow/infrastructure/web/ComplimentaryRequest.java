package com.ticketflow.infrastructure.web;

import com.ticketflow.usecase.IssueComplimentaryCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /events/{id}/complimentary}. {@code reason} is optional short text for the audit
 * trail (recipient, campaign...): no control characters, stored as data and never rendered as HTML.
 */
public record ComplimentaryRequest(
        @NotNull @Min(1) @Max(IssueComplimentaryCommand.MAX_QUANTITY) Integer quantity,
        @Size(max = IssueComplimentaryCommand.MAX_REASON_LENGTH)
        @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters") String reason) {}
