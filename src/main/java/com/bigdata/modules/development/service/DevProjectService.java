package com.bigdata.modules.development.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.modules.development.dto.CreateFileRequest;
import com.bigdata.modules.development.dto.CreateFolderRequest;
import com.bigdata.modules.development.entity.DevFileMeta;
import com.bigdata.modules.development.entity.DevProject;
import com.bigdata.modules.development.mapper.DevProjectMapper;
import com.bigdata.modules.development.util.FileUtils;
import com.bigdata.modules.development.util.GitPathUtils;
import com.bigdata.modules.development.vo.FileContentVO;
import com.bigdata.modules.development.vo.GitAuthor;
import com.bigdata.modules.development.vo.GitCommitInfo;
import com.bigdata.modules.development.vo.GitFileInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class DevProjectService {

    private static final Logger log = LoggerFactory.getLogger(DevProjectService.class);

    private final DevProjectMapper devProjectMapper;
    private final GitRepositoryService gitRepositoryService;
    private final DevFileMetaService devFileMetaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public DevProjectService(DevProjectMapper devProjectMapper,
                             GitRepositoryService gitRepositoryService,
                             DevFileMetaService devFileMetaService) {
        this.devProjectMapper = devProjectMapper;
        this.gitRepositoryService = gitRepositoryService;
        this.devFileMetaService = devFileMetaService;
    }

    public Page<DevProject> getProjectList(int current, int size, String projectName, String projectCode, Integer status) {
        Page<DevProject> page = new Page<>(current, size);
        LambdaQueryWrapper<DevProject> wrapper = new LambdaQueryWrapper<>();

        if (projectName != null && !projectName.isEmpty()) {
            wrapper.like(DevProject::getProjectName, projectName);
        }
        if (projectCode != null && !projectCode.isEmpty()) {
            wrapper.like(DevProject::getProjectCode, projectCode);
        }
        if (status != null) {
            wrapper.eq(DevProject::getStatus, status);
        }

        wrapper.orderByDesc(DevProject::getCreateTime);

        return devProjectMapper.selectPage(page, wrapper);
    }

    public DevProject getProjectById(Long id) {
        return devProjectMapper.selectById(id);
    }

    @Transactional
    public void saveProject(DevProject project) throws IOException, GitAPIException {
        devProjectMapper.insert(project);

        gitRepositoryService.initRepository(project.getId());

        log.info("项目创建成功并初始化Git仓库: projectId={}, projectName={}",
                project.getId(), project.getProjectName());
    }

    @Transactional
    public void updateProject(DevProject project) {
        devProjectMapper.updateById(project);
    }

    @Transactional
    public void deleteProject(Long id) throws IOException {
        DevProject project = devProjectMapper.selectById(id);
        if (project != null && gitRepositoryService.repositoryExists(id)) {
            Path repoPath = getRepoPathFromService(id);
            FileUtils.deleteDirectory(repoPath);
        }

        devProjectMapper.deleteById(id);
        log.info("项目已删除: projectId={}", id);
    }

    public long countByStatus(Integer status) {
        LambdaQueryWrapper<DevProject> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(DevProject::getStatus, status);
        }
        return devProjectMapper.selectCount(wrapper);
    }

    public List<GitFileInfo> getFileTree(Long projectId, String path, String branch) {
        try {
            ensureRepositoryExists(projectId);
            return gitRepositoryService.getFileTree(projectId, path, branch);
        } catch (Exception e) {
            log.error("获取文件树失败: projectId={}", projectId, e);
            throw new GitOperationException("获取文件树失败: " + e.getMessage(), e);
        }
    }

    public FileContentVO readFileContent(Long projectId, String filePath, String branch) {
        try {
            ensureRepositoryExists(projectId);
            String content = gitRepositoryService.readFileContent(projectId, filePath, branch);

            FileContentVO vo = new FileContentVO();
            vo.setProjectId(projectId);
            vo.setFilePath(filePath);
            vo.setContent(content);
            vo.setSize(content != null ? content.getBytes().length : 0);

            String fileName = filePath;
            if (filePath.contains("/")) {
                fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
            }
            vo.setName(fileName);

            if (filePath.contains("/")) {
                vo.setParentPath(filePath.substring(0, filePath.lastIndexOf("/")));
            }

            DevFileMeta meta = devFileMetaService.getByProjectAndFilePath(projectId, filePath);
            if (meta != null) {
                vo.setDatasources(parseJsonList(meta.getDatasources()));
                vo.setRemark(meta.getRemark());
                vo.setEngine(meta.getEngine());
                vo.setRunParams(parseJsonMap(meta.getRunParams()));
                vo.setComputeResources(parseJsonMap(meta.getComputeResources()));

                if (meta.getJarDependencyCatalogs() != null && !meta.getJarDependencyCatalogs().isEmpty()) {
                    try {
                        List<String> catalogs = objectMapper.readValue(
                                meta.getJarDependencyCatalogs(),
                                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                        );
                        vo.setJarDependencyCatalogs(catalogs);
                    } catch (Exception e) {
                        log.warn("反序列化依赖包分类失败", e);
                    }
                }
            }

            return vo;
        } catch (Exception e) {
            log.error("读取文件内容失败: projectId={}, filePath={}", projectId, filePath, e);
            throw new GitOperationException("读取文件内容失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> createFile(CreateFileRequest request, String author, String email) {
        try {
            ensureRepositoryExists(request.getProjectId());

            String filePath = buildFilePath(request.getParentPath(), request.getName());
            GitAuthor gitAuthor = new GitAuthor(author, email);

            gitRepositoryService.writeFileContent(
                    request.getProjectId(),
                    filePath,
                    request.getContent(),
                    gitAuthor,
                    "Create file: " + request.getName()
            );

            devFileMetaService.saveFileMeta(
                    request.getProjectId(),
                    filePath,
                    request.getDatasources(),
                    request.getJarDependencyCatalogs(),
                    request.getRemark(),
                    request.getEngine(),
                    request.getRunParams(),
                    request.getComputeResources()
            );

            return Map.of(
                    "success", true,
                    "message", "文件创建成功",
                    "path", filePath
            );
        } catch (Exception e) {
            log.error("创建文件失败", e);
            throw new GitOperationException("创建文件失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> updateFile(Long projectId, String filePath, String content,
                                           String author, String email, String message) {
        try {
            ensureRepositoryExists(projectId);

            GitAuthor gitAuthor = new GitAuthor(author, email);
            gitRepositoryService.writeFileContent(
                    projectId,
                    filePath,
                    content,
                    gitAuthor,
                    message != null ? message : ("Update: " + filePath)
            );

            return Map.of(
                    "success", true,
                    "message", "文件更新成功"
            );
        } catch (Exception e) {
            log.error("更新文件失败: projectId={}, filePath={}", projectId, filePath, e);
            throw new GitOperationException("更新文件失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> updateFile(CreateFileRequest request, String author, String email) {
        try {
            ensureRepositoryExists(request.getProjectId());

            String filePath = request.getFilePath();
            if (filePath == null || filePath.isEmpty()) {
                filePath = buildFilePath(request.getParentPath(), request.getName());
            }

            GitAuthor gitAuthor = new GitAuthor(author, email);
            gitRepositoryService.writeFileContent(
                    request.getProjectId(),
                    filePath,
                    request.getContent(),
                    gitAuthor,
                    request.getMessage() != null ? request.getMessage() : ("Update: " + filePath)
            );

            devFileMetaService.saveFileMeta(
                    request.getProjectId(),
                    filePath,
                    request.getDatasources(),
                    request.getJarDependencyCatalogs(),
                    request.getRemark(),
                    request.getEngine(),
                    request.getRunParams(),
                    request.getComputeResources()
            );

            return Map.of(
                    "success", true,
                    "message", "文件更新成功",
                    "path", filePath
            );
        } catch (Exception e) {
            log.error("更新文件失败", e);
            throw new GitOperationException("更新文件失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> createFolder(CreateFolderRequest request, String author, String email) {
        try {
            ensureRepositoryExists(request.getProjectId());

            String folderPath = buildFilePath(request.getParentPath(), request.getName());
            GitAuthor gitAuthor = new GitAuthor(author, email);

            gitRepositoryService.createFolder(
                    request.getProjectId(),
                    folderPath,
                    gitAuthor,
                    "Create folder: " + request.getName()
            );

            return Map.of(
                    "success", true,
                    "message", "文件夹创建成功",
                    "path", folderPath
            );
        } catch (Exception e) {
            log.error("创建文件夹失败", e);
            throw new GitOperationException("创建文件夹失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> deleteFileOrFolder(Long projectId, String path, String author, String email) {
        try {
            ensureRepositoryExists(projectId);

            GitAuthor gitAuthor = new GitAuthor(author, email);
            gitRepositoryService.deleteFileOrFolder(projectId, path, gitAuthor, "Delete: " + path);

            return Map.of(
                    "success", true,
                    "message", "删除成功"
            );
        } catch (Exception e) {
            log.error("删除失败: projectId={}, path={}", projectId, path, e);
            throw new GitOperationException("删除失败: " + e.getMessage(), e);
        }
    }

    public List<GitCommitInfo> getCommitHistory(Long projectId, String branch, int page, int size, String filePath) {
        try {
            ensureRepositoryExists(projectId);
            return gitRepositoryService.getCommitHistory(projectId, branch, page, size, filePath);
        } catch (Exception e) {
            log.error("获取提交历史失败: projectId={}", projectId, e);
            throw new GitOperationException("获取提交历史失败: " + e.getMessage(), e);
        }
    }

    public GitCommitInfo getCommitDetail(Long projectId, String commitId) {
        try {
            ensureRepositoryExists(projectId);
            return gitRepositoryService.getCommitDetail(projectId, commitId);
        } catch (Exception e) {
            log.error("获取提交详情失败: projectId={}, commitId={}", projectId, commitId, e);
            throw new GitOperationException("获取提交详情失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> getFileVersionAtCommit(Long projectId, String filePath, String commitId) {
        try {
            ensureRepositoryExists(projectId);
            String content = gitRepositoryService.getFileVersionAtCommit(projectId, filePath, commitId);

            return Map.of(
                    "commitId", commitId,
                    "filePath", filePath,
                    "content", content
            );
        } catch (Exception e) {
            log.error("获取文件版本失败", e);
            throw new GitOperationException("获取文件版本失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> getDiff(Long projectId, String oldCommitId, String newCommitId, String filePath) {
        try {
            ensureRepositoryExists(projectId);
            String diff = gitRepositoryService.getDiffBetweenCommits(projectId, oldCommitId, newCommitId, filePath);

            return Map.of(
                    "oldCommitId", oldCommitId,
                    "newCommitId", newCommitId,
                    "diff", diff
            );
        } catch (Exception e) {
            log.error("获取差异对比失败", e);
            throw new GitOperationException("获取差异对比失败: " + e.getMessage(), e);
        }
    }

    public List<String> listBranches(Long projectId) {
        try {
            ensureRepositoryExists(projectId);
            return gitRepositoryService.listBranches(projectId);
        } catch (Exception e) {
            log.error("获取分支列表失败: projectId={}", projectId, e);
            throw new GitOperationException("获取分支列表失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> createBranch(Long projectId, String branchName, String startPoint) {
        try {
            ensureRepositoryExists(projectId);
            gitRepositoryService.createBranch(projectId, branchName, startPoint);

            return Map.of(
                    "success", true,
                    "message", "分支创建成功",
                    "branchName", branchName
            );
        } catch (Exception e) {
            log.error("创建分支失败", e);
            throw new GitOperationException("创建分支失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> deleteBranch(Long projectId, String branchName) {
        try {
            ensureRepositoryExists(projectId);
            gitRepositoryService.deleteBranch(projectId, branchName);

            return Map.of(
                    "success", true,
                    "message", "分支删除成功"
            );
        } catch (Exception e) {
            log.error("删除分支失败", e);
            throw new GitOperationException("删除分支失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> switchBranch(Long projectId, String branchName) {
        try {
            ensureRepositoryExists(projectId);
            gitRepositoryService.switchBranch(projectId, branchName);

            return Map.of(
                    "success", true,
                    "message", "切换分支成功",
                    "currentBranch", branchName
            );
        } catch (Exception e) {
            log.error("切换分支失败", e);
            throw new GitOperationException("切换分支失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> rollbackToCommit(Long projectId, String commitId, String author, String email) {
        try {
            ensureRepositoryExists(projectId);

            GitAuthor gitAuthor = new GitAuthor(author, email);
            gitRepositoryService.rollbackToCommit(projectId, commitId, gitAuthor);

            return Map.of(
                    "success", true,
                    "message", "回滚成功",
                    "rollbackToCommit", commitId
            );
        } catch (Exception e) {
            log.error("回滚失败", e);
            throw new GitOperationException("回滚失败: " + e.getMessage(), e);
        }
    }

    private void ensureRepositoryExists(Long projectId) {
        if (!gitRepositoryService.repositoryExists(projectId)) {
            throw new GitOperationException(
                    "项目的Git仓库不存在，请检查项目是否正确初始化: projectId=" + projectId
            );
        }
    }

    private String buildFilePath(String parentPath, String name) {
        return GitPathUtils.toRelativePath(parentPath, name);
    }

    private Path getRepoPathFromService(Long projectId) {
        if (gitRepositoryService instanceof GitRepositoryServiceImpl impl) {
            return Path.of(impl.getRepositoriesBasePath(), String.valueOf(projectId));
        }
        throw new UnsupportedOperationException("无法获取仓库路径");
    }

    public static class GitOperationException extends RuntimeException {
        public GitOperationException(String message) {
            super(message);
        }

        public GitOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("反序列化JSON列表失败", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("反序列化JSON Map失败", e);
            return Map.of();
        }
    }
}
