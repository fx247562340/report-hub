package com.reporthub.service;

import com.reporthub.common.BizException;
import com.reporthub.domain.UserAccount;
import com.reporthub.repo.UserAccountRepo;
import com.reporthub.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {
    private final UserAccountRepo userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserAccountRepo userRepo, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Map<String, Object> login(String username, String password) {
        UserAccount user = userRepo.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException("用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getRole());
        return profile(user, token);
    }

    public Map<String, Object> me(String userId) {
        UserAccount user = userRepo.findById(userId).orElseThrow(() -> new BizException("用户不存在"));
        return profile(user, null);
    }

    private Map<String, Object> profile(UserAccount user, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("displayName", user.getDisplayName());
        m.put("role", user.getRole());
        if (token != null) m.put("token", token);
        return m;
    }
}
