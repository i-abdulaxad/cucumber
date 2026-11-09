package com.example.authservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Invalid email format")
        @StorableText(trimmed = true, message = "Email must not contain control characters or invalid Unicode characters")
        String email,

        @NotBlank(message = "Password cannot be blank")
        String password
) {
}
