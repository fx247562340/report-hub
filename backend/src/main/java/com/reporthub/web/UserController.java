package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.common.BizException;
import com.reporthub.domain.UserAccount;
import com.reporthub.repo.UserAccountRepo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class UserController {
    private final UserAccountRepo userRepo;
    private final PasswordEncoder encoder;

    public UserController(UserAccountRepo userRepo, PasswordEncoder encoder) {
        this.userRepo = userRepo;
        this.encoder = encoder;
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(userRepo.findAll().stream().map(this::toView).toList());
    }

    @PostMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String role = body.getOrDefault("role", "member");
        String displayName = body.getOrDefault("displayName", username);
        if (username.isBlank()) throw new BizException("用户名不能为空");
        if (password.length() < 6) throw new BizException("密码至少 6 位");
        if (userRepo.findByUsername(username) != null) throw new BizException("用户名已存在");
        if (!"admin".equals(role) && !"member".equals(role)) throw new BizException("角色只能是 admin 或 member");

        UserAccount u = new UserAccount();
        u.setId(UUID.randomUUID().toString());
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u.setDisplayName(displayName);
        u.setCreatedAt(Instant.now());
        return ApiResponse.ok(toView(userRepo.save(u)));
    }

    @PostMapping("/admin/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        UserAccount u = userRepo.findById(id).orElseThrow(() -> new BizException("用户不存在"));
        if (body.containsKey("displayName")) u.setDisplayName(body.get("displayName"));
        if (body.containsKey("role")) {
            String role = body.get("role");
            if (!"admin".equals(role) && !"member".equals(role)) throw new BizException("角色只能是 admin 或 member");
            u.setRole(role);
        }
        if (body.containsKey("password") && !body.get("password").isBlank()) {
            if (body.get("password").length() < 6) throw new BizException("密码至少 6 位");
            u.setPasswordHash(encoder.encode(body.get("password")));
        }
        return ApiResponse.ok(toView(userRepo.save(u)));
    }

    @DeleteMapping("/admin/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        if (id.equals(auth.getName())) throw new BizException("不能删除当前登录账号");
        UserAccount u = userRepo.findById(id).orElseThrow(() -> new BizException("用户不存在"));
        if ("admin".equals(u.getUsername())) throw new BizException("内置管理员不可删除");
        userRepo.deleteById(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/change-password")
    public ApiResponse<Void> changePassword(Authentication auth, @RequestBody Map<String, String> body) {
        UserAccount u = userRepo.findById(auth.getName()).orElseThrow(() -> new BizException("用户不存在"));
        String oldPwd = body.getOrDefault("oldPassword", "");
        String newPwd = body.getOrDefault("newPassword", "");
        if (!encoder.matches(oldPwd, u.getPasswordHash())) throw new BizException("原密码不正确");
        if (newPwd.length() < 6) throw new BizException("新密码至少 6 位");
        u.setPasswordHash(encoder.encode(newPwd));
        userRepo.save(u);
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/profile")
    public ApiResponse<Map<String, Object>> updateProfile(Authentication auth, @RequestBody Map<String, String> body) {
        UserAccount u = userRepo.findById(auth.getName()).orElseThrow(() -> new BizException("用户不存在"));
        if (body.containsKey("displayName")) u.setDisplayName(body.get("displayName"));
        return ApiResponse.ok(toView(userRepo.save(u)));
    }

    private Map<String, Object> toView(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }
}
