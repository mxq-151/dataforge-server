package com.bigdata.modules.development.service;

import com.bigdata.modules.development.util.FileUtils;
import com.bigdata.modules.development.util.GitPathUtils;
import com.bigdata.modules.development.util.LanguageDetector;
import com.bigdata.modules.development.vo.GitAuthor;
import com.bigdata.modules.development.vo.GitCommitInfo;
import com.bigdata.modules.development.vo.GitFileChange;
import com.bigdata.modules.development.vo.GitFileInfo;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GitRepositoryServiceImpl implements GitRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryServiceImpl.class);

    private final String repositoriesBasePath;

    public GitRepositoryServiceImpl(
            @Value("${git.repositories.base-path:/data/bigdata-platform/git-repositories}") String repositoriesBasePath) {
        this.repositoriesBasePath = repositoriesBasePath;
    }

    public String getRepositoriesBasePath() {
        return repositoriesBasePath;
    }

    @Override
    public boolean repositoryExists(Long projectId) {
        Path repoPath = getRepositoryPath(projectId);
        return Files.exists(repoPath.resolve(".git"));
    }

    @Override
    public void initRepository(Long projectId) throws IOException, GitAPIException {
        Path repoPath = getRepositoryPath(projectId);
        Files.createDirectories(repoPath);

        log.info("初始化Git仓库: projectId={}, path={}", projectId, repoPath);

        Git.init().setDirectory(repoPath.toFile()).call();

        createInitialCommit(projectId, repoPath);
    }

    @Override
    public void cloneRepository(String remoteUrl, Long projectId) throws IOException, GitAPIException {
        Path repoPath = getRepositoryPath(projectId);
        Files.createDirectories(repoPath.getParent());

        log.info("克隆Git仓库: url={}, projectId={}", remoteUrl, projectId);

        Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(repoPath.toFile())
                .call();
    }

    @Override
    public List<GitFileInfo> getFileTree(Long projectId, String path, String branch) throws IOException, GitAPIException {
        List<GitFileInfo> result = new ArrayList<>();

        try (Repository repository = openRepository(projectId)) {
            ObjectId branchObjectId = resolveBranch(repository, branch);
            if (branchObjectId == null) {
                return result;
            }

            try (RevWalk revWalk = new RevWalk(repository);
                 TreeWalk treeWalk = new TreeWalk(repository)) {

                RevCommit commit = revWalk.parseCommit(branchObjectId);
                setupTreeWalk(treeWalk, commit.getTree(), path, repository);

                while (treeWalk.next()) {
                    result.add(createFileInfo(treeWalk, repository, path));
                }
            }
        }

        return sortFileInfoList(result);
    }

    @Override
    public String readFileContent(Long projectId, String filePath, String branch) throws IOException, GitAPIException {
        try (Repository repository = openRepository(projectId)) {
            ObjectId branchObjectId = resolveBranchOrThrow(repository, branch);

            try (RevWalk revWalk = new RevWalk(repository);
                 TreeWalk treeWalk = new TreeWalk(repository)) {

                RevCommit commit = revWalk.parseCommit(branchObjectId);
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(GitPathUtils.toGitPath(filePath)));

                if (treeWalk.next()) {
                    return readBlobContent(repository, treeWalk.getObjectId(0));
                }
            }
        }

        throw new GitFileNotFoundException("文件不存在: " + filePath);
    }

    @Override
    public void writeFileContent(Long projectId, String filePath, String content,
                                 GitAuthor author, String message) throws IOException, GitAPIException {
        String normalizedPath = GitPathUtils.toGitPath(filePath);

        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            Path fullPath = getRepositoryPath(projectId).resolve(normalizedPath);
            Files.createDirectories(fullPath.getParent());
            Files.writeString(fullPath, content != null ? content : "", StandardCharsets.UTF_8);

            stageAndCommit(git, normalizedPath, author,
                    message != null && !message.isEmpty() ? message : "Update " + filePath);

            log.info("文件已写入并提交: projectId={}, file={}", projectId, filePath);
        }
    }

    @Override
    public void createFolder(Long projectId, String folderPath,
                             GitAuthor author, String message) throws IOException, GitAPIException {
        String normalizedPath = GitPathUtils.toGitPath(folderPath);

        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            Path folderFullPath = getRepositoryPath(projectId).resolve(normalizedPath);
            Files.createDirectories(folderFullPath);

            Path keepFile = folderFullPath.resolve(".gitkeep");
            if (!Files.exists(keepFile)) {
                Files.writeString(keepFile, "", StandardCharsets.UTF_8);

                String gitKeepPath = normalizedPath + "/.gitkeep";
                git.add().addFilepattern(gitKeepPath).call();

                String commitMessage = message != null && !message.isEmpty() ?
                        message : "Create folder " + folderPath;

                commitChanges(git, author, commitMessage);
            }

            log.info("文件夹已创建: projectId={}, path={}", projectId, folderPath);
        }
    }

    @Override
    public void deleteFileOrFolder(Long projectId, String path,
                                   GitAuthor author, String message) throws IOException, GitAPIException {
        String normalizedPath = GitPathUtils.toGitPath(path);

        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            Path fullPath = getRepositoryPath(projectId).resolve(normalizedPath);
            if (Files.isDirectory(fullPath)) {
                FileUtils.deleteDirectory(fullPath);
            } else {
                Files.deleteIfExists(fullPath);
            }

            git.rm().addFilepattern(normalizedPath).call();

            String commitMessage = message != null && !message.isEmpty() ?
                    message : "Delete " + path;

            commitChanges(git, author, commitMessage);

            log.info("文件/文件夹已删除: projectId={}, path={}", projectId, path);
        }
    }

    @Override
    public List<GitCommitInfo> getCommitHistory(Long projectId, String branch, int page, int size, String filePath)
            throws IOException, GitAPIException {
        List<GitCommitInfo> history = new ArrayList<>();

        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            LogCommand logCommand = git.log()
                    .setMaxCount(size)
                    .setSkip((page - 1) * size);

            if (filePath != null && !filePath.trim().isEmpty()) {
                String normalizedPath = GitPathUtils.toGitPath(filePath);
                logCommand.addPath(normalizedPath);
            }

            Iterable<RevCommit> commits = logCommand.call();

            for (RevCommit commit : commits) {
                history.add(buildCommitInfo(repository, commit));
            }
        }

        return history;
    }

    @Override
    public GitCommitInfo getCommitDetail(Long projectId, String commitId) throws IOException {
        try (Repository repository = openRepository(projectId)) {
            ObjectId objectId = repository.resolve(commitId);
            if (objectId == null) {
                throw new GitCommitNotFoundException("提交不存在: " + commitId);
            }

            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(objectId);
                return buildDetailedCommitInfo(repository, commit);
            }
        }
    }

    @Override
    public String getFileVersionAtCommit(Long projectId, String filePath, String commitId) throws IOException {
        try (Repository repository = openRepository(projectId)) {
            ObjectId commitObjectId = repository.resolve(commitId);
            if (commitObjectId == null) {
                throw new GitCommitNotFoundException("提交不存在: " + commitId);
            }

            try (RevWalk revWalk = new RevWalk(repository);
                 TreeWalk treeWalk = new TreeWalk(repository)) {

                RevCommit commit = revWalk.parseCommit(commitObjectId);
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(GitPathUtils.toGitPath(filePath)));

                if (treeWalk.next()) {
                    return readBlobContent(repository, treeWalk.getObjectId(0));
                }
            }
        }

        throw new GitFileNotFoundException("在指定提交中未找到文件: " + filePath);
    }

    @Override
    public String getDiffBetweenCommits(Long projectId, String oldCommitId, String newCommitId, String filePath)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (Repository repository = openRepository(projectId);
             DiffFormatter diffFormatter = new DiffFormatter(out)) {

            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);

            ObjectId oldObjectId = requireResolve(repository, oldCommitId, "旧提交");
            ObjectId newObjectId = requireResolve(repository, newCommitId, "新提交");

            List<DiffEntry> diffs = computeDiffs(repository, oldObjectId, newObjectId, filePath);
            diffFormatter.format(diffs);
        }

        return out.toString(StandardCharsets.UTF_8.name());
    }

    @Override
    public List<String> listBranches(Long projectId) throws IOException, GitAPIException {
        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            return git.branchList().call().stream()
                    .map(ref -> {
                        String name = ref.getName();
                        if (name.startsWith("refs/heads/")) {
                            return name.substring("refs/heads/".length());
                        }
                        return name;
                    })
                    .collect(Collectors.toList());
        }
    }

    @Override
    public void createBranch(Long projectId, String branchName, String startPoint) throws IOException, GitAPIException {
        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            git.branchCreate()
                    .setName(branchName)
                    .setStartPoint(startPoint != null ? startPoint : "HEAD")
                    .call();

            log.info("分支创建成功: projectId={}, branch={}", projectId, branchName);
        }
    }

    @Override
    public void deleteBranch(Long projectId, String branchName) throws IOException, GitAPIException {
        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            git.branchDelete()
                    .setBranchNames(branchName)
                    .setForce(true)
                    .call();

            log.info("分支删除成功: projectId={}, branch={}", projectId, branchName);
        }
    }

    @Override
    public void switchBranch(Long projectId, String branchName) throws IOException, GitAPIException {
        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            git.checkout().setName(branchName).call();

            log.info("切换分支成功: projectId={}, branch={}", projectId, branchName);
        }
    }

    @Override
    public void rollbackToCommit(Long projectId, String commitId, GitAuthor author) throws IOException, GitAPIException {
        try (Repository repository = openRepository(projectId);
             Git git = new Git(repository)) {

            git.reset()
                    .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef(commitId)
                    .call();

            git.commit()
                    .setAuthor(author.name(), author.email())
                    .setMessage("Rollback to commit " + commitId)
                    .setAllowEmpty(true)
                    .call();

            log.info("已回滚到提交: projectId={}, commitId={}", projectId, commitId);
        }
    }

    private Path getRepositoryPath(Long projectId) {
        return Paths.get(repositoriesBasePath, String.valueOf(projectId));
    }

    private Repository openRepository(Long projectId) throws IOException {
        Path repoPath = getRepositoryPath(projectId);
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        return builder.setGitDir(repoPath.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build();
    }

    private void createInitialCommit(Long projectId, Path repoPath) throws IOException, GitAPIException {
        Repository repository = openRepository(projectId);

        Path gitignoreFile = repoPath.resolve(".gitignore");
        if (!Files.exists(gitignoreFile)) {
            Files.writeString(gitignoreFile, getDefaultGitignoreContent(), StandardCharsets.UTF_8);
        }

        try (Git git = new Git(repository)) {
            git.add().addFilepattern(".gitignore").call();
            git.commit().setMessage("Initial commit: Add .gitignore").call();
        }
    }

    private String getDefaultGitignoreContent() {
        return """
                # IDE
                .idea/
                .vscode/
                *.iml

                # OS
                .DS_Store
                Thumbs.db

                # Java
                target/
                *.class
                *.jar
                *.war
                *.ear
                """;
    }

    private void setupTreeWalk(TreeWalk treeWalk, RevTree tree, String path, Repository repository) throws IOException {
        if (GitPathUtils.isRootPath(path)) {
            treeWalk.addTree(tree);
            treeWalk.setRecursive(false);
            return;
        }

        String normalizedPath = GitPathUtils.toGitPath(path);
        String[] pathParts = normalizedPath.split("/");
        
        try (RevWalk revWalk = new RevWalk(repository)) {
            ObjectId currentTreeId = tree.getId();
            
            for (int i = 0; i < pathParts.length; i++) {
                String part = pathParts[i];
                boolean isLastPart = (i == pathParts.length - 1);
                
                try (TreeWalk tw = new TreeWalk(repository)) {
                    tw.addTree(currentTreeId);
                    tw.setRecursive(false);
                    tw.setFilter(PathFilter.create(part));
                    
                    if (!tw.next()) {
                        log.warn("路径不存在: {} (在部分路径 {} 后未找到 {})", normalizedPath, 
                                String.join("/", java.util.Arrays.copyOf(pathParts, i)), part);
                        treeWalk.addTree(tree);
                        treeWalk.setRecursive(false);
                        return;
                    }
                    
                    if (isLastPart) {
                        ObjectId targetId = tw.getObjectId(0);

                        if (tw.isSubtree()) {
                            treeWalk.addTree(targetId);
                            treeWalk.setRecursive(false);
                            log.debug("成功定位到子目录: {}", normalizedPath);
                        } else {
                            treeWalk.addTree(tree);
                            treeWalk.setRecursive(false);
                            treeWalk.setFilter(PathFilter.create(normalizedPath));
                            log.debug("定位到文件: {}", normalizedPath);
                        }
                    } else {
                        if (!tw.isSubtree()) {
                            log.warn("路径 {} 的第 {} 段不是目录", normalizedPath, i);
                            treeWalk.addTree(tree);
                            treeWalk.setRecursive(false);
                            return;
                        }
                        currentTreeId = tw.getObjectId(0);
                    }
                }
            }
        }
    }

    private GitFileInfo createFileInfo(TreeWalk treeWalk, Repository repository, String parentPath) throws IOException {
        String name = treeWalk.getNameString();
        String relativePath = treeWalk.getPathString();
        boolean isFolder = treeWalk.isSubtree();

        String fullPath;
        if (GitPathUtils.isRootPath(parentPath) || parentPath == null || parentPath.isEmpty()) {
            fullPath = relativePath;
        } else {
            fullPath = parentPath + "/" + relativePath;
        }

        if (isFolder) {
            return new GitFileInfo(name, fullPath, "folder", null, 0);
        }

        String language = LanguageDetector.detectFromFileName(name);
        long fileSize = computeFileSize(repository, treeWalk);

        return new GitFileInfo(name, fullPath, "file", language, fileSize);
    }

    private long computeFileSize(Repository repository, TreeWalk treeWalk) throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            return reader.getObjectSize(treeWalk.getObjectId(0), Constants.OBJ_BLOB);
        }
    }

    private List<GitFileInfo> sortFileInfoList(List<GitFileInfo> files) {
        return files.stream()
                .sorted(Comparator
                        .comparing(GitFileInfo::type, (a, b) -> a.equals("folder") ? -1 : 1)
                        .thenComparing(GitFileInfo::name, String::compareToIgnoreCase))
                .collect(Collectors.toList());
    }

    private ObjectId resolveBranchOrThrow(Repository repository, String branch) throws IOException {
        ObjectId objectId = resolveBranch(repository, branch);
        if (objectId == null) {
            throw new GitBranchNotFoundException("分支不存在: " + branch);
        }
        return objectId;
    }

    private ObjectId resolveBranch(Repository repository, String branch) throws IOException {
        if (branch == null || branch.isEmpty() || "main".equals(branch) || "master".equals(branch)) {
            return repository.resolve("HEAD");
        }
        return repository.resolve("refs/heads/" + branch);
    }

    private String readBlobContent(Repository repository, ObjectId blobId) throws IOException {
        try (ObjectReader reader = repository.newObjectReader()) {
            byte[] data = reader.open(blobId).getBytes();
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private void stageAndCommit(Git git, String filePattern, GitAuthor author, String message)
            throws GitAPIException {
        git.add().addFilepattern(filePattern).call();
        commitChanges(git, author, message);
    }

    private void commitChanges(Git git, GitAuthor author, String message) throws GitAPIException {
        git.commit()
                .setAuthor(author.name(), author.email())
                .setMessage(message)
                .call();
    }

    private GitCommitInfo buildCommitInfo(Repository repository, RevCommit commit) throws IOException {
        List<String> files = extractChangedFiles(repository, commit);

        return GitCommitInfo.builder()
                .commitId(commit.getId().getName())
                .shortMessage(commit.getShortMessage())
                .fullMessage(commit.getFullMessage())
                .authorName(commit.getAuthorIdent().getName())
                .authorEmail(commit.getAuthorIdent().getEmailAddress())
                .commitTime(Instant.ofEpochSecond(commit.getCommitTime()))
                .changedFiles(files)
                .build();
    }

    private GitCommitInfo buildDetailedCommitInfo(Repository repository, RevCommit commit) throws IOException {
        List<GitFileChange> changes = extractFileChanges(repository, commit);

        return GitCommitInfo.builder()
                .commitId(commit.getId().getName())
                .shortMessage(commit.getShortMessage())
                .fullMessage(commit.getFullMessage())
                .authorName(commit.getAuthorIdent().getName())
                .authorEmail(commit.getAuthorIdent().getEmailAddress())
                .commitTime(Instant.ofEpochSecond(commit.getCommitTime()))
                .changes(changes)
                .build();
    }

    private List<String> extractChangedFiles(Repository repository, RevCommit commit) {
        List<String> files = new ArrayList<>();
        if (commit.getParentCount() > 0) {
            try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repository);
                try {
                    List<DiffEntry> diffs = diffFormatter.scan(commit.getParent(0), commit);
                    for (DiffEntry entry : diffs) {
                        files.add(entry.getChangeType().name() + ": " + entry.getPath(DiffEntry.Side.NEW));
                    }
                } catch (IOException e) {
                    log.warn("获取变更文件列表失败", e);
                }
            }
        }
        return files;
    }

    private List<GitFileChange> extractFileChanges(Repository repository, RevCommit commit) {
        List<GitFileChange> changes = new ArrayList<>();
        if (commit.getParentCount() > 0) {
            try (DiffFormatter diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
                diffFormatter.setRepository(repository);
                diffFormatter.setDetectRenames(true);
                try {
                    List<DiffEntry> diffs = diffFormatter.scan(commit.getParent(0), commit);
                    for (DiffEntry entry : diffs) {
                        changes.add(convertToFileChange(entry));
                    }
                } catch (IOException e) {
                    log.warn("获取文件变更详情失败", e);
                }
            }
        }
        return changes;
    }

    private GitFileChange convertToFileChange(DiffEntry entry) {
        return new GitFileChange(
                convertChangeType(entry.getChangeType()),
                entry.getOldPath(),
                entry.getNewPath()
        );
    }

    private GitFileChange.ChangeType convertChangeType(DiffEntry.ChangeType jgitType) {
        return switch (jgitType) {
            case ADD -> GitFileChange.ChangeType.ADD;
            case MODIFY -> GitFileChange.ChangeType.MODIFY;
            case DELETE -> GitFileChange.ChangeType.DELETE;
            case RENAME -> GitFileChange.ChangeType.RENAME;
            case COPY -> GitFileChange.ChangeType.COPY;
            default -> GitFileChange.ChangeType.MODIFY;
        };
    }

    private ObjectId requireResolve(Repository repository, String commitId, String label) throws IOException {
        ObjectId objectId = repository.resolve(commitId);
        if (objectId == null) {
            throw new GitCommitNotFoundException(label + "不存在: " + commitId);
        }
        return objectId;
    }

    private List<DiffEntry> computeDiffs(Repository repository, ObjectId oldObjectId,
                                         ObjectId newObjectId, String filePath) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit oldCommit = revWalk.parseCommit(oldObjectId);
            RevCommit newCommit = revWalk.parseCommit(newObjectId);

            AbstractTreeIterator oldTreeParser = prepareTreeParser(repository, oldCommit.getTree());
            AbstractTreeIterator newTreeParser = prepareTreeParser(repository, newCommit.getTree());

            DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);

            List<DiffEntry> diffs = diffFormatter.scan(oldTreeParser, newTreeParser);

            if (filePath != null && !filePath.isEmpty()) {
                String normalizedPath = GitPathUtils.toGitPath(filePath);
                diffs = diffs.stream()
                        .filter(d -> d.getPath(DiffEntry.Side.NEW).equals(normalizedPath) ||
                                d.getOldPath().equals(normalizedPath))
                        .toList();
            }

            return diffs;
        }
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId treeId) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader()) {
            parser.reset(reader, treeId);
        }
        return parser;
    }

    public static class GitFileNotFoundException extends RuntimeException {
        public GitFileNotFoundException(String message) {
            super(message);
        }
    }

    public static class GitCommitNotFoundException extends RuntimeException {
        public GitCommitNotFoundException(String message) {
            super(message);
        }
    }

    public static class GitBranchNotFoundException extends RuntimeException {
        public GitBranchNotFoundException(String message) {
            super(message);
        }
    }
}
