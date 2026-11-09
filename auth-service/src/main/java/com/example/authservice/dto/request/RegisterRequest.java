package com.example.authservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Invalid email format")
        @Size(max = 254, message = "Email must be at most 254 characters long")
        @StorableText(trimmed = true, message = "Email must not contain control characters or invalid Unicode characters")
        String email,

        @NotBlank(message = "Password cannot be blank")
        @Size(min = 8, message = "Password must be at least 8 characters long")
        @MaxUtf8Bytes(value = 72, message = "Password must be at most 72 bytes long")
        String password,

        @NotBlank(message = "Full name cannot be blank")
        @VisibleText(message = "Full name cannot be blank")
        @MaxCodePoints(value = 100, message = "Full name must be at most 100 characters long")
        @StorableText(message = "Full name must not contain control characters or invalid Unicode characters")
        String fullName
) {
}
