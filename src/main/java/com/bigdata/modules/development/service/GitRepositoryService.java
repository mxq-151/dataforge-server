package com.bigdata.modules.development.service;

import com.bigdata.modules.development.vo.GitAuthor;
import com.bigdata.modules.development.vo.GitCommitInfo;
import com.bigdata.modules.development.vo.GitFileInfo;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.util.List;

public interface GitRepositoryService {

    boolean repositoryExists(Long projectId);

    void initRepository(Long projectId) throws IOException, GitAPIException;

    void cloneRepository(String remoteUrl, Long projectId) throws IOException, GitAPIException;

    List<GitFileInfo> getFileTree(Long projectId, String path, String branch) throws IOException, GitAPIException;

    String readFileContent(Long projectId, String filePath, String branch) throws IOException, GitAPIException;

    void writeFileContent(Long projectId, String filePath, String content,
                          GitAuthor author, String message) throws IOException, GitAPIException;

    void createFolder(Long projectId, String folderPath,
                      GitAuthor author, String message) throws IOException, GitAPIException;

    void deleteFileOrFolder(Long projectId, String path,
                            GitAuthor author, String message) throws IOException, GitAPIException;

    List<GitCommitInfo> getCommitHistory(Long projectId, String branch, int page, int size, String filePath)
            throws IOException, GitAPIException;

    GitCommitInfo getCommitDetail(Long projectId, String commitId) throws IOException;

    String getFileVersionAtCommit(Long projectId, String filePath, String commitId) throws IOException;

    String getDiffBetweenCommits(Long projectId, String oldCommitId, String newCommitId, String filePath)
            throws IOException;

    List<String> listBranches(Long projectId) throws IOException, GitAPIException;

    void createBranch(Long projectId, String branchName, String startPoint) throws IOException, GitAPIException;

    void deleteBranch(Long projectId, String branchName) throws IOException, GitAPIException;

    void switchBranch(Long projectId, String branchName) throws IOException, GitAPIException;

    void rollbackToCommit(Long projectId, String commitId, GitAuthor author) throws IOException, GitAPIException;
}
