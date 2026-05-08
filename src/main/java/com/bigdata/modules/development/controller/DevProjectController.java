package com.bigdata.modules.development.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.DevProjectDTO;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.service.DevProjectService;
import com.bigdata.modules.development.vo.FolderTreeNode;
import com.bigdata.modules.development.vo.GitFileInfo;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/development/project")
public class DevProjectController {

    @Autowired
    private DevProjectService devProjectService;

    @GetMapping("/list")
    public Result<Page<DevProject>> getList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Integer status) {
        Page<DevProject> result = devProjectService.getProjectList(current, size, projectName, projectCode, status);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<DevProject> getById(@PathVariable Long id) {
        DevProject project = devProjectService.getProjectById(id);
        return Result.success(project);
    }

    @PostMapping
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
        DevProject project = new DevProject();
        BeanUtils.copyProperties(dto, project);
        devProjectService.updateProject(project);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            devProjectService.deleteProject(id);
            return Result.success();
        } catch (Exception e) {
            return Result.error("删除项目失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/folders")
    public Result<List<FolderTreeNode>> getFolderList(@PathVariable Long id) {
        try {
            List<GitFileInfo> allFolders = new ArrayList<>();
            collectAllFolders(id, null, allFolders);

            List<FolderTreeNode> folderTree = buildFolderTree(allFolders);
            return Result.success(folderTree);
        } catch (Exception e) {
            return Result.error("获取文件夹列表失败: " + e.getMessage());
        }
    }

    private void collectAllFolders(Long projectId, String parentPath, List<GitFileInfo> allFolders) throws Exception {
        List<GitFileInfo> currentLevel = devProjectService.getFileTree(projectId, parentPath, "main");
        List<GitFileInfo> folders = currentLevel.stream()
                .filter(GitFileInfo::isFolder)
                .collect(Collectors.toList());

        for (GitFileInfo folder : folders) {
            allFolders.add(folder);
            collectAllFolders(projectId, folder.path(), allFolders);
        }
    }

    private List<FolderTreeNode> buildFolderTree(List<GitFileInfo> folders) {
        if (folders == null || folders.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, FolderTreeNode> nodeMap = new LinkedHashMap<>();

        for (GitFileInfo folder : folders) {
            String path = folder.path() != null ? folder.path() : "";
            String name = folder.name() != null ? folder.name() : "unknown";

            FolderTreeNode node = FolderTreeNode.of(name, path);
            nodeMap.put(path, node);
        }

        Set<String> rootPaths = new LinkedHashSet<>();
        
        for (GitFileInfo folder : folders) {
            String path = folder.path() != null ? folder.path() : "";

            if (!path.contains("/")) {
                rootPaths.add(path);
            } else {
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                
                if (nodeMap.containsKey(parentPath)) {
                    FolderTreeNode parentNode = nodeMap.get(parentPath);
                    FolderTreeNode childNode = nodeMap.get(path);
                    
                    FolderTreeNode updatedParent = new FolderTreeNode(
                            parentNode.name(),
                            parentNode.path(),
                            appendChild(parentNode.children(), childNode)
                    );
                    nodeMap.put(parentPath, updatedParent);
                } else {
                    rootPaths.add(path);
                }
            }
        }

        return rootPaths.stream()
                .map(nodeMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<FolderTreeNode> appendChild(List<FolderTreeNode> children, FolderTreeNode child) {
        List<FolderTreeNode> newChildren = new ArrayList<>(children);
        newChildren.add(child);
        return newChildren;
    }
}
