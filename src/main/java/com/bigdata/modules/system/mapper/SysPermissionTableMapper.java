package com.bigdata.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.system.entity.SysPermissionTable;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysPermissionTableMapper extends BaseMapper<SysPermissionTable> {

    @Select("SELECT table_metadata_id FROM sys_permission_table WHERE table_permission_id = #{tablePermissionId}")
    List<Long> selectTableMetadataIdsByPermissionId(@Param("tablePermissionId") Long tablePermissionId);

    @Select("<script>" +
            "SELECT table_permission_id AS tablePermissionId, table_metadata_id AS tableMetadataId FROM sys_permission_table WHERE table_permission_id IN " +
            "<foreach item='permId' collection='permissionIds' open='(' separator=',' close=')'>" +
            "#{permId}" +
            "</foreach>" +
            "</script>")
    List<SysPermissionTable> selectByPermissionIds(@Param("permissionIds") List<Long> permissionIds);

    @Delete("DELETE FROM sys_permission_table WHERE table_permission_id = #{tablePermissionId}")
    int deleteByPermissionId(@Param("tablePermissionId") Long tablePermissionId);

    @Insert("INSERT INTO sys_permission_table(table_permission_id, table_metadata_id, create_time) VALUES(#{tablePermissionId}, #{tableMetadataId}, #{createTime})")
    void insertPermissionTable(@Param("tablePermissionId") Long tablePermissionId, @Param("tableMetadataId") Long tableMetadataId, @Param("createTime") LocalDateTime createTime);

    @Insert("<script>" +
            "INSERT INTO sys_permission_table(table_permission_id, table_metadata_id, create_time) VALUES " +
            "<foreach item='item' collection='list' separator=','>" +
            "(#{item.tablePermissionId}, #{item.tableMetadataId}, #{item.createTime})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("list") List<SysPermissionTable> list);
}
