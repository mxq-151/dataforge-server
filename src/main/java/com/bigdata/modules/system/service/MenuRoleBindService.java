package com.bigdata.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.system.entity.SysMenuPermission;
import com.bigdata.modules.system.entity.SysRole;
import com.bigdata.modules.system.entity.SysRoleMenuPermission;
import com.bigdata.modules.system.mapper.SysMenuPermissionMapper;
import com.bigdata.modules.system.mapper.SysRoleMapper;
import com.bigdata.modules.system.mapper.SysRoleMenuPermissionMapper;
import com.bigdata.modules.system.vo.MenuRoleBindVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Service
public class MenuRoleBindService {

    @Autowired
    private SysMenuPermissionMapper sysMenuPermissionMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysRoleMenuPermissionMapper sysRoleMenuPermissionMapper;

    public List<SysMenuPermission> getMenuList(String menuName) {
        LambdaQueryWrapper<SysMenuPermission> wrapper = new LambdaQueryWrapper<>();
        if (menuName != null && !menuName.isEmpty()) {
            wrapper.like(SysMenuPermission::getMenuName, menuName);
        }
        wrapper.eq(SysMenuPermission::getStatus, 1);
        wrapper.orderByAsc(SysMenuPermission::getSortOrder);
        return sysMenuPermissionMapper.selectList(wrapper);
    }

    public void deleteByPermissionId(Long id)
    {
        this.sysRoleMenuPermissionMapper.deleteById(id);
    }

    public List<MenuRoleBindVO> getBinds() {

        List<SysMenuPermission> menus = getMenuList(null);

        LambdaQueryWrapper<SysRoleMenuPermission> wrapper = new LambdaQueryWrapper<>();
        List<SysRoleMenuPermission> rolePermissions=this.sysRoleMenuPermissionMapper.selectList(wrapper);
        
        List<MenuRoleBindVO> result = new ArrayList<>();
        for(SysRoleMenuPermission srp:rolePermissions)
        {
            for(SysMenuPermission menu:menus){
                if (menu.getId()==srp.getMenuPermissionId())
                {
                    MenuRoleBindVO vo = new MenuRoleBindVO();
                    vo.setId(srp.getId());
                    vo.setMenuName(menu.getMenuName());
                    vo.setMenuCode(menu.getMenuCode());
                    vo.setPath(menu.getPath());
                    vo.setIcon(menu.getIcon());
                    vo.setSortOrder(menu.getSortOrder());
                    vo.setVisible(menu.getVisible());
                    vo.setStatus(menu.getStatus());
                    vo.setRoleId(srp.getRoleId());
                    SysRole sr=this.sysRoleMapper.selectById(srp.getRoleId());
                    if(sr==null)
                    {
                        continue;
                    }
                    vo.setRoleCode(sr.getRoleCode());
                    vo.setRoleName(sr.getRoleName());
                    result.add(vo);
                    break;
                }   
            }
            
        }
        return result;
    }

    @Transactional
    public void bindRoles(Long menuId, Long roleId) {

        SysMenuPermission menu = sysMenuPermissionMapper.selectById(menuId);
        if (menu == null) {
            throw new BusinessException("菜单不存在");
        }


        SysRole role = sysRoleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在: " + roleId);
        }
    
        SysRoleMenuPermission bind = new SysRoleMenuPermission();
        bind.setRoleId(roleId);
        bind.setMenuPermissionId(menuId);
        bind.setCreateTime(LocalDateTime.now());
        sysRoleMenuPermissionMapper.insertPermission(bind);
        
    }
}
