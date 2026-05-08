package com.bigdata.modules.system.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.LoginDTO;
import com.bigdata.modules.system.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/system/auth")
public class AuthController {

    @Autowired
    private SysUserService sysUserService;

    @PostMapping("/login")
    public Result<Map<String, String>> login(@Valid @RequestBody LoginDTO loginDTO) {
        String token = sysUserService.login(loginDTO.getUsername(), loginDTO.getPassword());
        Map<String, String> data = new HashMap<>();
        data.put("token", token);
        return Result.success(data);
    }

    @PostMapping("/logout")
    public Result<String> logout() {
        return Result.success("登出成功");
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> getUserInfo() {
        Map<String, Object> data = new HashMap<>();
        data.put("username", "admin");
        data.put("roles", new String[]{"ROLE_USER"});
        return Result.success(data);
    }
}
