package com.example.authservice.service;

import com.example.authservice.dto.request.LoginRequest;
import com.example.authservice.dto.response.LoginResponse;
import com.example.authservice.dto.request.RegisterRequest;
import com.example.authservice.dto.response.UserResponse;

public interface AuthService {
    UserResponse register(RegisterRequest registerRequest);

    LoginResponse login(LoginRequest loginRequest);
}
