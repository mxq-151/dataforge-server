package com.bigdata.modules.system.controller;

import com.bigdata.common.annotation.AuditLog;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.system.dto.MenuRoleBindDTO;
import com.bigdata.modules.system.entity.SysMenuPermission;
import com.bigdata.modules.system.service.MenuRoleBindService;
import com.bigdata.modules.system.vo.MenuPermissionVO;
import com.bigdata.modules.system.vo.MenuRoleBindVO;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/menu-role")
public class MenuRoleBindController {

    @Autowired
    private MenuRoleBindService menuRoleBindService;

    @GetMapping("/list")
    public Result<List<MenuPermissionVO>> listMenus(
            @RequestParam(required = false) String menuName) {
        List<SysMenuPermission> menus = menuRoleBindService.getMenuList(menuName);
        List<MenuPermissionVO> voList = menus.stream().map(this::toMenuVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @AuditLog(module = "权限管理", operation = "绑定菜单角色", targetType = "MenuRole")
    @PostMapping("/bind")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> bind(@Valid @RequestBody List<MenuRoleBindDTO> dto) {
        for(MenuRoleBindDTO dt:dto)
        {
            menuRoleBindService.bindRoles(dt.getMenuId(), dt.getRoleId());
        }
        
        return Result.success();
    }

    @AuditLog(module = "权限管理", operation = "删除菜单角色绑定", targetType = "MenuRole")
    @DeleteMapping("/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<MenuPermissionVO>> delete(
            @RequestParam(required = false) Long id) {
        this.menuRoleBindService.deleteByPermissionId(id);
        return Result.success();
    }

    @GetMapping("/binds")
    public Result<List<MenuRoleBindVO>> binds() {
        List<MenuRoleBindVO> binds = menuRoleBindService.getBinds();
        return Result.success(binds);
    }

    private MenuPermissionVO toMenuVO(SysMenuPermission entity) {
        MenuPermissionVO vo = new MenuPermissionVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
