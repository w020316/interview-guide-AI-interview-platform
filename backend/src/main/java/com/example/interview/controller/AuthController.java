package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.UserEntity;
import com.example.interview.repository.UserRepository;
import com.example.interview.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口（无需 token 即可访问）
 * POST /api/auth/register  — 注册
 * POST /api/auth/login     — 登录，返回 JWT
 * POST /api/auth/logout    — 登出（前端清 token，服务端无状态）
 */
@Tag(name = "认证", description = "用户注册与登录")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 注册
     * Body: {"username":"alice","password":"123456","email":"a@b.com"}
     */
    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<String> register(@RequestBody Map<String, String> req) {
        String username = req.get("username");
        String password = req.get("password");
        String email    = req.get("email");

        if (username == null || password == null) {
            return Result.error(400, "用户名和密码不能为空");
        }
        if (userRepository.existsByUsername(username)) {
            return Result.error(400, "用户名已存在");
        }
        if (email != null && userRepository.existsByEmail(email)) {
            return Result.error(400, "邮箱已被注册");
        }

        UserEntity user = UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .email(email)
                .build();
        userRepository.save(user);

        return Result.success(jwtUtil.generateToken(username));
    }

    /**
     * 登录
     * Body: {"username":"alice","password":"123456"}
     */
    @Operation(summary = "用户登录，返回 JWT token")
    @PostMapping("/login")
    public Result<String> login(@RequestBody Map<String, String> req) {
        String username = req.get("username");
        String password = req.get("password");

        if (username == null || password == null) {
            return Result.error(400, "用户名和密码不能为空");
        }

        UserEntity user = userRepository.findByUsername(username)
                .orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return Result.error(401, "用户名或密码错误");
        }

        return Result.success(jwtUtil.generateToken(username));
    }

    /**
     * 登出
     * 当前 JWT 为无状态方案，登出仅由前端清除 token 即可。
     * 此接口保留供未来扩展 token 黑名单使用，当前返回成功。
     */
    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success(null);
    }
}
