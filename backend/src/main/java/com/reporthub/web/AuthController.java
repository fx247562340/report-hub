package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.login(body.get("username"), body.get("password")));
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.register(body.get("username"), body.get("password"), body.get("displayName")));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication auth) {
        return ApiResponse.ok(authService.me(auth.getName()));
    }
}
