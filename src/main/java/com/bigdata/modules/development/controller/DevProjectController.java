package com.bigdata.modules.development.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.DevProjectDTO;
import com.bigdata.modules.development.dto.ProgramParamsDTO;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.entity.ProgramParams;
import com.bigdata.modules.development.service.DevProjectService;
import com.bigdata.modules.development.service.ProgramParamsService;
import com.bigdata.modules.development.vo.DevProjectVO;
import com.bigdata.modules.development.vo.ProgramParamsVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/development/project")
public class DevProjectController {

    @Autowired
    private DevProjectService devProjectService;

    @Autowired
    private ProgramParamsService programParamsService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/list")
    public Result<Page<DevProjectVO>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Integer status) {
        Page<DevProject> page = devProjectService.getProjectList(current, size, projectName, projectCode, status);
        Page<DevProjectVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return Result.success(voPage);
    }

    @GetMapping("/{id}")
    public Result<DevProjectVO> getById(@PathVariable Long id) {
        dataAccessPermissionService.checkProjectPermission(id, DataAccessPermissionService.PROJECT_PERMISSION_READ);
        DevProject project = devProjectService.getProjectById(id);
        return Result.success(toVO(project));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> save(@Valid @RequestBody DevProjectDTO dto) {
        try {
            DevProject project = new DevProject();
            BeanUtils.copyProperties(dto, project);
            devProjectService.saveProject(project);
            return Result.success();
        } catch (Exception e) {
            return Result.error("保存项目失败: " + e.getMessage());
        }
    }

    @PutMapping
    public Result<Void> update(@Valid @RequestBody DevProjectDTO dto) {
        if (dto.getId() != null) {
            dataAccessPermissionService.checkProjectPermission(dto.getId(), DataAccessPermissionService.PROJECT_PERMISSION_EDIT);
        }
        DevProject project = new DevProject();
        BeanUtils.copyProperties(dto, project);
        devProjectService.updateProject(project);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        dataAccessPermissionService.checkProjectPermission(id, DataAccessPermissionService.PROJECT_PERMISSION_DELETE);
        try {
            devProjectService.deleteProject(id);
            return Result.success();
        } catch (Exception e) {
            return Result.error("删除项目失败: " + e.getMessage());
        }
    }


    @GetMapping("/{projectId}/params")
    public Result<ProgramParamsVO> getProgramParams(
            @PathVariable Long projectId,
            @RequestParam(required = true) String filePath) {
        dataAccessPermissionService.checkProjectPermission(projectId, DataAccessPermissionService.PROJECT_PERMISSION_READ);
        ProgramParams params = programParamsService.getByProjectIdAndFilePath(projectId, filePath);
        if (params == null) {
            throw new BusinessException("未找到对应的程序参数记录");
        }
        return Result.success(toParamsVO(params));
    }

    @PostMapping("/{projectId}/params")
    public Result<Void> saveProgramParams(
            @PathVariable Long projectId,
            @Valid @RequestBody ProgramParamsDTO dto) {
        dataAccessPermissionService.checkProjectPermission(projectId, DataAccessPermissionService.PROJECT_PERMISSION_EDIT);
        try {
            dto.setProjectId(projectId);
            programParamsService.save(dto);
            return Result.success();
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("保存程序参数失败: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/params")
    public Result<Void> updateProgramParams(
            @PathVariable Long projectId,
            @RequestParam(required = true) String filePath,
            @Valid @RequestBody ProgramParamsDTO dto) {
        dataAccessPermissionService.checkProjectPermission(projectId, DataAccessPermissionService.PROJECT_PERMISSION_EDIT);
        try {
            dto.setProjectId(projectId);
            dto.setFilePath(filePath);
            programParamsService.update(dto);
            return Result.success();
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("更新程序参数失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{projectId}/params")
    public Result<Void> deleteProgramParams(
            @PathVariable Long projectId,
            @RequestParam(required = true) String filePath) {
        dataAccessPermissionService.checkProjectPermission(projectId, DataAccessPermissionService.PROJECT_PERMISSION_DELETE);
        try {
            programParamsService.delete(projectId, filePath);
            return Result.success();
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("删除程序参数失败: " + e.getMessage());
        }
    }

    private DevProjectVO toVO(DevProject entity) {
        DevProjectVO vo = new DevProjectVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private ProgramParamsVO toParamsVO(ProgramParams entity) {
        ProgramParamsVO vo = new ProgramParamsVO();
        vo.setProjectId(entity.getProjectId());
        vo.setFilePath(entity.getFilePath());
        vo.setEngine(entity.getEngine());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());

        try {
            if (entity.getDatasources() != null && !entity.getDatasources().isEmpty()) {
                vo.setDatasources(objectMapper.readValue(entity.getDatasources(), new TypeReference<List<String>>() {}));
            }
            if (entity.getJarDependencyCatalogs() != null && !entity.getJarDependencyCatalogs().isEmpty()) {
                vo.setJarDependencyCatalogs(objectMapper.readValue(entity.getJarDependencyCatalogs(), new TypeReference<List<String>>() {}));
            }
            if (entity.getRunParams() != null && !entity.getRunParams().isEmpty()) {
                vo.setRunParams(objectMapper.readValue(entity.getRunParams(), new TypeReference<java.util.Map<String, Object>>() {}));
            }
            if (entity.getComputeResources() != null && !entity.getComputeResources().isEmpty()) {
                vo.setComputeResources(objectMapper.readValue(entity.getComputeResources(), new TypeReference<java.util.Map<String, Object>>() {}));
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON反序列化失败: " + e.getMessage());
        }

        return vo;
    }
}
