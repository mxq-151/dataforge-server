package com.bigdata.modules.system.mapper;

import com.bigdata.modules.system.entity.SysFilePermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 文件路径权限表在 PostgreSQL 下的读写回归。
 *
 * <p>该表包含列名 {@code recursive}。MySQL 8 把 RECURSIVE 列为保留字，实体上因此写了
 * MySQL 风格的反引号；PostgreSQL 不支持反引号，导致该表任何 select/insert/update
 * 都报 {@code syntax error at or near "`"}。容器部署用的正是 PostgreSQL。</p>
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("System模块 - 文件路径权限表跨方言读写")
class SysFilePermissionPgCompatibilityTest {

    @Autowired
    private SysFilePermissionMapper sysFilePermissionMapper;

    @Test
    @DisplayName("插入并回读：recursive 列在两个方言下都能正常读写")
    void insertAndReadRecursiveColumn() {
        SysFilePermission permission = new SysFilePermission();
        permission.setStorageConfigId(1L);
        permission.setPermissionName("UT-文件权限");
        permission.setFilePath("/data/demo");
        permission.setPermissionType(1);
        permission.setRecursive(1);
        permission.setDescription("单元测试");

        int inserted = sysFilePermissionMapper.insert(permission);
        assertEquals(1, inserted);
        assertNotNull(permission.getId());

        SysFilePermission reloaded = sysFilePermissionMapper.selectById(permission.getId());
        assertNotNull(reloaded);
        assertEquals(1, reloaded.getRecursive(), "recursive 标记必须原样回读");
        assertEquals("/data/demo", reloaded.getFilePath());
        assertEquals(1, reloaded.getStorageConfigId());
    }
}
