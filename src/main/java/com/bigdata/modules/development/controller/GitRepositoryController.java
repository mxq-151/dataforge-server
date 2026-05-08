package com.bigdata.modules.development.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.CreateFileRequest;
import com.bigdata.modules.development.dto.CreateFolderRequest;
import com.bigdata.modules.development.service.DevProjectService;
import com.bigdata.modules.development.vo.GitCommitInfo;
import com.bigdata.modules.development.vo.GitFileInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/development/git")
public class GitRepositoryController {

    private final DevProjectService devProjectService;

    @Autowired
    public GitRepositoryController(DevProjectService devProjectService) {
        this.devProjectService = devProjectService;
    }

    @GetMapping("/{projectId}/tree")
    public Result<List<GitFileInfo>> getFileTree(
            @PathVariable Long projectId,
            @RequestParam(required = false) String path,
            @RequestParam(required = false, defaultValue = "main") String branch) {
        List<GitFileInfo> tree = devProjectService.getFileTree(projectId, path, branch);
        return Result.success(tree);
    }

    @GetMapping("/{projectId}/file")
    public Result<Map<String, Object>> readFileContent(
            @PathVariable Long projectId,
            @RequestParam String filePath,
            @RequestParam(required = false, defaultValue = "main") String branch) {
        Map<String, Object> content = devProjectService.readFileContent(projectId, filePath, branch);
        return Result.success(content);
    }

    @PostMapping("/{projectId}/file")
    public Result<Map<String, Object>> createFile(@PathVariable Long projectId,
                                                   @RequestBody CreateFileRequest request) {
        request.setProjectId(projectId);
        Map<String, Object> result = devProjectService.createFile(
                request, "System", "system@bigdata-platform.com");
        return Result.success(result);
    }

    @PutMapping("/{projectId}/file")
    public Result<Map<String, Object>> updateFile(
            @PathVariable Long projectId,
            @RequestParam String filePath,
            @RequestBody Map<String, String> body) {

        String content = body.get("content");
        String message = body.get("message");

        Map<String, Object> result = devProjectService.updateFile(
                projectId, filePath, content, "System", "system@bigdata-platform.com", message);
        return Result.success(result);
    }

    @DeleteMapping("/{projectId}/file")
    public Result<Map<String, Object>> deleteFileOrFolder(
            @PathVariable Long projectId,
            @RequestParam String path) {

        Map<String, Object> result = devProjectService.deleteFileOrFolder(
                projectId, path, "System", "system@bigdata-platform.com");
        return Result.success(result);
    }

    @PostMapping("/{projectId}/folder")
    public Result<Map<String, Object>> createFolder(@PathVariable Long projectId,
                                                     @RequestBody CreateFolderRequest request) {
        request.setProjectId(projectId);
        Map<String, Object> result = devProjectService.createFolder(
                request, "System", "system@bigdata-platform.com");
        return Result.success(result);
    }

    @GetMapping("/{projectId}/commits")
    public Result<List<GitCommitInfo>> getCommitHistory(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "main") String branch,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String filePath) {

        List<GitCommitInfo> history = devProjectService.getCommitHistory(projectId, branch, page, size, filePath);
        return Result.success(history);
    }

    @GetMapping("/{projectId}/commit/{commitId}")
    public Result<GitCommitInfo> getCommitDetail(
            @PathVariable Long projectId,
            @PathVariable String commitId) {

        GitCommitInfo detail = devProjectService.getCommitDetail(projectId, commitId);
        return Result.success(detail);
    }

    @GetMapping("/{projectId}/version")
    public Result<Map<String, Object>> getFileVersionAtCommit(
            @PathVariable Long projectId,
            @RequestParam String filePath,
            @RequestParam String commitId) {

        Map<String, Object> version = devProjectService.getFileVersionAtCommit(projectId, filePath, commitId);
        return Result.success(version);
    }

    @GetMapping("/{projectId}/diff")
    public Result<Map<String, Object>> getDiff(
            @PathVariable Long projectId,
            @RequestParam String oldCommitId,
            @RequestParam String newCommitId,
            @RequestParam(required = false) String filePath) {

        Map<String, Object> diff = devProjectService.getDiff(projectId, oldCommitId, newCommitId, filePath);
        return Result.success(diff);
    }

    @GetMapping("/{projectId}/branches")
    public Result<List<String>> listBranches(@PathVariable Long projectId) {
        List<String> branches = devProjectService.listBranches(projectId);
        return Result.success(branches);
    }

    @PostMapping("/{projectId}/branches")
    public Result<Map<String, Object>> createBranch(
            @PathVariable Long projectId,
            @RequestParam String branchName,
            @RequestParam(required = false) String startPoint) {

        Map<String, Object> result = devProjectService.createBranch(projectId, branchName, startPoint);
        return Result.success(result);
    }

    @DeleteMapping("/{projectId}/branches")
    public Result<Map<String, Object>> deleteBranch(
            @PathVariable Long projectId,
            @RequestParam String branchName) {

        Map<String, Object> result = devProjectService.deleteBranch(projectId, branchName);
        return Result.success(result);
    }

    @PostMapping("/{projectId}/branches/checkout")
    public Result<Map<String, Object>> switchBranch(
            @PathVariable Long projectId,
            @RequestParam String branchName) {

        Map<String, Object> result = devProjectService.switchBranch(projectId, branchName);
        return Result.success(result);
    }

    @PostMapping("/{projectId}/rollback")
    public Result<Map<String, Object>> rollbackToCommit(
            @PathVariable Long projectId,
            @RequestParam String commitId) {

        Map<String, Object> result = devProjectService.rollbackToCommit(
                projectId, commitId, "System", "system@bigdata-platform.com");
        return Result.success(result);
    }
}
