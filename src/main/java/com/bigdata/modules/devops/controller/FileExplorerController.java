package com.bigdata.modules.devops.controller;

import com.bigdata.common.annotation.AuditLog;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.service.FileDataReaderService;
import com.bigdata.modules.devops.service.FileExplorerService;
import com.bigdata.modules.devops.vo.FileDataVO;
import com.bigdata.modules.devops.vo.FileInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/file/explorer")
public class FileExplorerController {

    @Autowired
    private FileExplorerService fileExplorerService;

    @Autowired
    private FileDataReaderService fileDataReaderService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @GetMapping("/list")
    public Result<List<FileInfoVO>> listFiles(
            @RequestParam(defaultValue = "local") String storageType,
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(required = false) Long configId) {
        dataAccessPermissionService.checkFileReadPermission(path, configId);
        List<Map<String, Object>> fileList = fileExplorerService.listFiles(storageType, path, configId);
        if (!dataAccessPermissionService.isAdmin()) {
            fileList = fileList.stream()
                    .filter(item -> {
                        String itemPath = item.get("path") != null ? item.get("path").toString() : null;
                        if (itemPath == null) {
                            String itemName = item.get("name") != null ? item.get("name").toString() : "";
                            itemPath = path.endsWith("/") ? path + itemName : path + "/" + itemName;
                        }
                        return dataAccessPermissionService.hasFileReadPermission(itemPath, configId);
                    })
                    .collect(Collectors.toList());
        }
        List<FileInfoVO> voList = fileList.stream().map(FileInfoVO::fromMap).collect(Collectors.toList());
        return Result.success(voList);
    }

    @PostMapping("/directory")
    @AuditLog(module = "文件管理", operation = "创建目录", targetType = "File")
    public Result<Void> createDirectory(
            @RequestParam(defaultValue = "local") String storageType,
            @RequestParam(defaultValue = "/") String parentPath,
            @RequestParam String name,
            @RequestParam(required = false) Long configId) {
        String fullPath = parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;
        dataAccessPermissionService.checkFileWritePermission(fullPath, configId);
        fileExplorerService.createDirectory(storageType, parentPath, name, configId);
        return Result.success();
    }

    @DeleteMapping("/file")
    @AuditLog(module = "文件管理", operation = "删除文件或目录", targetType = "File")
    public Result<Void> deleteFile(
            @RequestParam(defaultValue = "local") String storageType,
            @RequestParam String path,
            @RequestParam(required = false) Long configId) {
        dataAccessPermissionService.checkFileWritePermission(path, configId);
        fileExplorerService.deleteFile(storageType, path, configId);
        return Result.success();
    }

    @PutMapping("/file")
    @AuditLog(module = "文件管理", operation = "重命名文件或目录", targetType = "File")
    public Result<Void> renameFile(
            @RequestParam(defaultValue = "local") String storageType,
            @RequestParam String path,
            @RequestParam String newName,
            @RequestParam(required = false) Long configId) {
        dataAccessPermissionService.checkFileWritePermission(path, configId);
        fileExplorerService.renameFile(storageType, path, newName, configId);
        return Result.success();
    }

    @PostMapping("/file/upload")
    @AuditLog(module = "文件管理", operation = "上传文件", targetType = "File")
    public Result<Void> uploadFile(
            @RequestParam(defaultValue = "local") String storageType,
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(required = false) Long configId,
            @RequestParam("file") MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename();
            if (fileName == null || fileName.isEmpty()) {
                return Result.error("文件名不能为空");
            }

            String filePath = path.endsWith("/") ? path + fileName : path + "/" + fileName;

            dataAccessPermissionService.checkFileWritePermission(filePath, configId);

            try (InputStream inputStream = file.getInputStream()) {
                fileExplorerService.uploadFile(storageType, filePath, inputStream, configId, file.getSize());
            }

            return Result.success();
        } catch (com.bigdata.common.exception.BusinessException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return Result.error("上传文件失败: " + e.getMessage());
        }
    }

    @GetMapping("/file/preview")
    public ResponseEntity<byte[]> previewFile(
            @RequestParam(defaultValue = "local") String storageType,
            @RequestParam String path,
            @RequestParam(required = false) Long configId) {
        dataAccessPermissionService.checkFileReadPermission(path, configId);
        try {
            InputStream inputStream = fileExplorerService.readFile(storageType, path, configId);
            byte[] content = inputStream.readAllBytes();
            inputStream.close();

            HttpHeaders headers = new HttpHeaders();

            String fileName = path.substring(path.lastIndexOf("/") + 1);
            String fileExtension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase() : "";

            switch (fileExtension) {
                case "txt":
                    headers.setContentType(MediaType.TEXT_PLAIN);
                    break;
                case "html":
                    headers.setContentType(MediaType.TEXT_HTML);
                    break;
                case "json":
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    break;
                case "xml":
                    headers.setContentType(MediaType.APPLICATION_XML);
                    break;
                case "pdf":
                    headers.setContentType(MediaType.APPLICATION_PDF);
                    break;
                case "png":
                    headers.setContentType(MediaType.IMAGE_PNG);
                    break;
                case "jpg":
                case "jpeg":
                    headers.setContentType(MediaType.IMAGE_JPEG);
                    break;
                case "gif":
                    headers.setContentType(MediaType.IMAGE_GIF);
                    break;
                case "csv":
                    headers.setContentType(MediaType.parseMediaType("text/csv"));
                    break;
                default:
                    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            }

            headers.setContentLength(content.length);
            headers.setCacheControl("no-cache");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/file/download")
    public ResponseEntity<byte[]> downloadFile(
            @RequestParam(defaultValue = "local") String storageType,
            @RequestParam String path,
            @RequestParam(required = false) Long configId) {
        dataAccessPermissionService.checkFileReadPermission(path, configId);
        try (InputStream inputStream = fileExplorerService.readFile(storageType, path, configId)) {
            byte[] content = inputStream.readAllBytes();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(content.length);

            String fileName = path.substring(path.lastIndexOf("/") + 1);
            headers.setContentDispositionFormData("attachment", fileName);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/storage-types")
    public Result<List<String>> getStorageTypes() {
        List<String> types = fileExplorerService.getSupportedStorageTypes();
        return Result.success(types);
    }

    @GetMapping("/data/read")
    public Result<FileDataVO> readFileData(@RequestParam String directoryPath) {
        FileDataVO vo = fileDataReaderService.readFileData(directoryPath);
        return Result.success(vo);
    }
}
