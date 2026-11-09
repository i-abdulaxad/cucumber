package com.example.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateOrganizationRequest(
        @NotBlank(message = "Organization name cannot be blank")
        @VisibleText(message = "Organization name cannot be blank")
        @MaxCodePoints(value = 100, message = "Organization name must be at most 100 characters long")
        @StorableText(message = "Organization name must not contain control characters or invalid Unicode characters")
        String name
) {
}
