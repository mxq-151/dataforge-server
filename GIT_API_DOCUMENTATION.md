# Git代码管理 API 接口文档

**基础路径**: `/development/git`

**版本**: v1.0.0  
**更新日期**: 2026-05-08  
**认证方式**: Bearer Token (JWT)

---

## 📋 目录

- [通用说明](#通用说明)
- [文件操作](#文件操作)
- [文件夹操作](#文件夹操作)
- [提交历史](#提交历史)
- [分支管理](#分支管理)
- [差异对比](#差异对比)
- [版本回滚](#版本回滚)

---

## 通用说明

### 响应格式

所有接口统一返回格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

### 错误响应

```json
{
  "code": 500,
  "message": "错误描述",
  "data": null
}
```

### 常见错误码

| HTTP状态码 | 说明 |
|-----------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 404 | 资源不存在（项目、文件、提交等） |
| 500 | 服务器内部错误 |

### 路径参数说明

- `projectId`: 项目ID (Long类型，从项目列表获取)
- `commitId`: Git提交的完整SHA或短SHA
- `branchName`: 分支名称 (如: main, master, feature-xxx)

---

## 文件操作

### 1. 获取文件树

获取指定项目的文件目录结构。

**Endpoint**: `GET /{projectId}/tree`

**Query Parameters**:

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| path | String | 否 | null | 指定目录路径，为空则返回根目录 |
| branch | String | 否 | main | 分支名称 |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "name": "src",
      "path": "src",
      "type": "folder",
      "language": null,
      "fileSize": 0
    },
    {
      "name": "README.md",
      "path": "README.md",
      "type": "file",
      "language": "markdown",
      "fileSize": 1024
    }
  ]
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 文件/文件夹名称 |
| path | String | 相对路径 |
| type | String | "file" 或 "folder" |
| language | String | 文件语言类型（仅文件） |
| fileSize | Long | 文件大小（字节，仅文件） |

**示例**:
```bash
curl -X GET "http://localhost:8080/development/git/1/tree?branch=main" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

```javascript
// 前端调用示例
const response = await fetch(`/development/git/${projectId}/tree?branch=main`, {
  headers: {
    'Authorization': `Bearer ${token}`
  }
});
const fileTree = await response.json();
```

---

### 2. 读取文件内容

读取指定文件的当前内容。

**Endpoint**: `GET /{projectId}/file`

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| filePath | String | ✅ | 文件路径（如：/src/Main.scala） |
| branch | String | 否 | 分支名称，默认main |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "path": "/src/Main.scala",
    "content": "object Main {\n  def main(args: Array[String]): Unit = ...\n}",
    "size": 156
  }
}
```

**示例**:
```bash
curl -X GET "http://localhost:8080/development/git/1/file?filePath=/src/Main.scala&branch=main"
```

```javascript
const fileContent = await fetch(
  `/development/git/${projectId}/file?filePath=${encodeURIComponent(filePath)}&branch=${branch}`,
  { headers: { 'Authorization': `Bearer ${token}` } }
).then(r => r.json());
console.log(fileContent.data.content);
```

---

### 3. 创建文件

创建新文件并自动提交到Git。

**Endpoint**: `POST /{projectId}/file`

**Request Body**:
```json
{
  "name": "Main.scala",
  "language": "scala",
  "content": "object Main { def main(args: Array[String]): Unit = println(\"Hello\") }",
  "projectId": 1,
  "parentPath": "/src/main/scala",
  "description": "主程序入口"
}
```

**字段说明**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | ✅ | 文件名（含扩展名） |
| language | String | 否 | 语言类型（用于IDE高亮） |
| content | String | ✅ | 文件内容 |
| projectId | Long | ✅ | 项目ID |
| parentPath | String | 否 | 父目录路径（默认根目录） |
| description | String | 否 | 文件描述 |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "文件创建成功",
    "path": "/src/main/scala/Main.scala"
  }
}
```

**示例**:
```javascript
const createFile = async (projectId, fileData) => {
  const response = await fetch(`/development/git/${projectId}/file`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({
      name: 'Main.scala',
      content: '// your code here',
      parentPath: '/src',
      language: 'scala'
    })
  });
  return response.json();
};
```

---

### 4. 更新文件内容

更新已存在的文件并提交。

**Endpoint**: `PUT /{projectId}/file`

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| filePath | String | ✅ | 要更新的文件路径 |

**Request Body**:
```json
{
  "content": "新的文件内容...",
  "message": "优化了性能"
}
```

**字段说明**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | String | ✅ | 新的文件内容 |
| message | String | 否 | 提交信息（默认："Update: {filePath}"） |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "文件更新成功"
  }
}
```

**示例**:
```javascript
const updateFile = async (projectId, filePath, newContent, commitMessage) => {
  const response = await fetch(`/development/git/${projectId}/file?filePath=${encodeURIComponent(filePath)}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({
      content: newContent,
      message: commitMessage || undefined
    })
  });
  return response.json();
};
```

---

### 5. 删除文件或文件夹

删除指定的文件或文件夹。

**Endpoint**: `DELETE /{projectId}/file`

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| path | String | ✅ | 要删除的文件或文件夹路径 |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "删除成功"
  }
}
```

**示例**:
```javascript
const deleteFile = async (projectId, path) => {
  const response = await fetch(
    `/development/git/${projectId}/file?path=${encodeURIComponent(path)}`,
    {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );
  return response.json();
};
```

---

## 文件夹操作

### 6. 创建文件夹

创建新文件夹并提交到Git。

**Endpoint**: `POST /{projectId}/folder`

**Request Body**:
```json
{
  "name": "src",
  "projectId": 1,
  "parentPath": "",
  "description": "源代码目录"
}
```

**字段说明**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | ✅ | 文件夹名称 |
| projectId | Long | ✅ | 项目ID |
| parentPath | String | 否 | 父目录路径 |
| description | String | 否 | 描述信息 |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "文件夹创建成功",
    "path": "/src"
  }
}
```

**示例**:
```javascript
const createFolder = async (projectId, folderName, parentPath) => {
  const response = await fetch(`/development/git/${projectId}/folder`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({
      name: folderName,
      parentPath: parentPath || ''
    })
  });
  return response.json();
};
```

---

## 提交历史

### 7. 获取提交历史列表

获取项目的Git提交历史记录。

**Endpoint**: `GET /{projectId}/commits`

**Query Parameters**:

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| branch | String | 否 | main | 分支名称 |
| page | Integer | 否 | 1 | 页码 |
| size | Integer | 否 | 20 | 每页数量 |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "commitId": "abc123def456...",
      "shortMessage": "Update Main.scala",
      "fullMessage": "Update Main.scala\n\n优化了性能并修复bug",
      "authorName": "张三",
      "authorEmail": "zhangsan@example.com",
      "commitTime": "2026-05-08T10:30:00Z",
      "changedFiles": [
        "MODIFY: src/Main.scala",
        "ADD: README.md"
      ],
      "changes": []
    }
  ]
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| commitId | String | 提交的完整SHA哈希 |
| shortMessage | String | 简短的提交信息（第一行） |
| fullMessage | String | 完整的提交信息 |
| authorName | String | 作者姓名 |
| authorEmail | String | 作者邮箱 |
| commitTime | Instant | 提交时间（ISO 8601格式） |
| changedFiles | List\<String\> | 变更的文件列表 |
| changes | List\<GitFileChange\> | 详细变更信息（仅在详情中） |

**示例**:
```javascript
const getCommitHistory = async (projectId, page = 1, size = 20) => {
  const response = await fetch(
    `/development/git/${projectId}/commits?page=${page}&size=${size}&branch=main`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );
  return response.json();
};

// 使用示例
const history = await getCommitHistory(1, 1, 10);
history.data.commits.forEach(commit => {
  console.log(`${commit.shortMessage} - ${commit.authorName}`);
});
```

---

### 8. 获取提交详情

获取单个提交的详细信息及变更对比。

**Endpoint**: `GET /{projectId}/commit/{commitId}`

**Path Variables**:

| 参数 | 类型 | 说明 |
|------|------|------|
| commitId | String | 提交ID（支持完整SHA和短SHA） |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "commitId": "abc123def456...",
    "shortMessage": "Update Main.scala",
    "fullMessage": "Update Main.scala\n\n详细说明...",
    "authorName": "张三",
    "authorEmail": "zhangsan@example.com",
    "commitTime": "2026-05-08T10:30:00Z",
    "changedFiles": [],
    "changes": [
      {
        "changeType": "MODIFY",
        "oldPath": "src/Main.scala",
        "newPath": "src/Main.scala"
      },
      {
        "changeType": "ADD",
        "oldPath": null,
        "newPath": "README.md"
      }
    ]
  }
}
```

**ChangeType 枚举值**:

| 值 | 说明 |
|---|------|
| ADD | 新增文件 |
| MODIFY | 修改文件 |
| DELETE | 删除文件 |
| RENAME | 重命名文件 |
| COPY | 复制文件 |

**示例**:
```javascript
const getCommitDetail = async (projectId, commitId) => {
  const response = await fetch(
    `/development/git/${projectId}/commit/${commitId}`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );
  return response.json();
};
```

---

### 9. 获取指定版本的文件内容

查看某个历史提交中的文件内容。

**Endpoint**: `GET /{projectId}/version`

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| filePath | String | ✅ | 文件路径 |
| commitId | String | ✅ | 目标提交ID |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "commitId": "abc123...",
    "filePath": "/src/Main.scala",
    "content": "object Main { ... } // 旧版本的内容"
  }
}
```

**示例**:
```javascript
const getFileVersion = async (projectId, filePath, commitId) => {
  const response = await fetch(
    `/development/git/${projectId}/version?filePath=${encodeURIComponent(filePath)}&commitId=${commitId}`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );
  return response.json();
};

// 使用场景：查看历史版本
const oldVersion = await getFileVersion(1, '/Main.scala', 'abc123');
console.log(oldVersion.data.content);
```

---

## 差异对比

### 10. 获取两个提交之间的差异

对比两个提交之间的变更，返回标准Git diff格式。

**Endpoint**: `GET /{projectId}/diff`

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| oldCommitId | String | ✅ | 旧提交ID |
| newCommitId | String | ✅ | 新提交ID |
| filePath | String | 否 | 指定文件路径（可选，不传则显示所有变更） |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "oldCommitId": "abc123...",
    "newCommitId": "def456...",
    "diff": "diff --git a/src/Main.scala b/src/Main.scala\nindex abc..def 100644\n--- a/src/Main.scala\n+++ b/src/Main.scala\n@@ -1,3 +1,4 @@\n object Main {\n+  // 新增注释\n   def main(args: Array[String]): Unit = ..."
  }
}
```

**示例**:
```javascript
const getDiff = async (projectId, oldCommitId, newCommitId, filePath) => {
  let url = `/development/git/${projectId}/diff?oldCommitId=${oldCommitId}&newCommitId=${newCommitId}`;
  if (filePath) {
    url += `&filePath=${encodeURIComponent(filePath)}`;
  }

  const response = await fetch(url, {
    headers: { 'Authorization': `Bearer ${token}` }
  });
  return response.json();
};

// 显示完整的diff
const diffResult = await getDiff(1, 'abc123', 'def456');
console.log(diffResult.data.diff);

// 只看某个文件的diff
const fileDiff = await getDiff(1, 'abc123', 'def456', '/src/Main.scala');
```

---

## 分支管理

### 11. 列出所有分支

获取项目的所有本地分支。

**Endpoint**: `GET /{projectId}/branches`

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    "main",
    "master",
    "feature/new-api",
    "hotfix/bug-fix"
  ]
}
```

**示例**:
```javascript
const listBranches = async (projectId) => {
  const response = await fetch(
    `/development/git/${projectId}/branches`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );
  return response.json();
};
```

---

### 12. 创建新分支

基于当前HEAD或指定起点创建新分支。

**Endpoint**: `POST /{projectId}/branches`

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| branchName | String | ✅ | 新分支名称 |
| startPoint | String | 否 | 起始点（commit ID或分支名），默认HEAD |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "分支创建成功",
    "branchName": "feature/new-feature"
  }
}
```

**示例**:
```javascript
const createBranch = async (projectId, branchName, startPoint) => {
  let url = `/development/git/${projectId}/branches?branchName=${encodeURIComponent(branchName)}`;
  if (startPoint) {
    url += `&startPoint=${startPoint}`;
  }

  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });
  return response.json();
};

// 从当前HEAD创建分支
await createBranch(1, 'feature/login');

// 从指定提交创建分支
await createBranch(1, 'hotfix/security-fix', 'abc123');
```

---

### 13. 删除分支

强制删除指定分支。

**Endpoint**: `DELETE /{projectId}/branches/{branchName}`

**Path Variables**:

| 参数 | 类型 | 说明 |
|------|------|------|
| branchName | String | 要删除的分支名 |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "分支删除成功"
  }
}
```

**示例**:
```javascript
const deleteBranch = async (projectId, branchName) => {
  const response = await fetch(
    `/development/git/${projectId}/branches/${encodeURIComponent(branchName)}`,
    {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );
  return response.json();
};
```

---

### 14. 切换分支

切换工作区到指定分支。

**Endpoint**: `POST /{projectId}/branches/{branchName}/checkout`

**Path Variables**:

| 参数 | 类型 | 说明 |
|------|------|------|
| branchName | String | 目标分支名 |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "切换分支成功",
    "currentBranch": "feature/new-api"
  }
}
```

**示例**:
```javascript
const switchBranch = async (projectId, branchName) => {
  const response = await fetch(
    `/development/git/${projectId}/branches/${encodeURIComponent(branchName)}/checkout`,
    {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );
  return response.json();
};

// 切换到feature分支
await switchBranch(1, 'feature/new-api');

// 切换回主分支
await switchBranch(1, 'main');
```

---

## 版本回滚

### 15. 回滚到指定提交

将仓库硬重置到指定提交，并创建一个回滚提交记录。

**Endpoint**: `POST /{projectId}/rollback`

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| commitId | String | ✅ | 目标提交ID（回滚到此版本） |

**Response**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "message": "回滚成功",
    "rollbackToCommit": "abc123..."
  }
}
```

**⚠️ 注意事项**：
- 此操作会永久丢弃回滚点之后的所有更改
- 回滚本身也会被记录为一个新提交
- 操作不可逆，请谨慎使用！

**示例**:
```javascript
const rollbackToCommit = async (projectId, commitId) => {
  const response = await fetch(
    `/development/git/${projectId}/rollback?commitId=${commitId}`,
    {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );
  return response.json();
};

// ⚠️ 危险操作！确认后再执行
if (confirm('确定要回滚吗？此操作不可逆！')) {
  const result = await rollbackToCommit(1, 'abc123...');
  console.log(result.data.message);
}
```

---

## 🎯 前端集成最佳实践

### 1. 文件浏览器组件

```vue
<template>
  <div class="file-explorer">
    <!-- 面包屑导航 -->
    <nav class="breadcrumb">
      <span @click="navigateTo('/')">📁 根目录</span>
      <span v-for="(segment, index) in pathSegments" :key="index">
        / <span @click="navigateTo(segment.path)">{{ segment.name }}</span>
      </span>
    </nav>

    <!-- 文件列表 -->
    <div class="file-list">
      <div
        v-for="item in files"
        :key="item.path"
        class="file-item"
        @click="handleItemClick(item)"
      >
        <span class="icon">{{ item.type === 'folder' ? '📁' : '📄' }}</span>
        <span class="name">{{ item.name }}</span>
        <span class="meta" v-if="item.type === 'file'">
          {{ formatFileSize(item.fileSize) }}
        </span>
      </div>
    </div>

    <!-- 文件编辑器 -->
    <div v-if="selectedFile" class="editor-panel">
      <h3>{{ selectedFile.name }}</h3>
      <textarea
        v-model="fileContent"
        :class="`language-${selectedFile.language}`"
      ></textarea>
      <button @click="saveFile">保存</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';

const props = defineProps(['projectId']);
const emit = defineEmits(['file-selected']);

const currentPath = ref('');
const files = ref([]);
const selectedFile = ref(null);
const fileContent = ref('');

const pathSegments = computed(() => {
  if (!currentPath.value) return [];
  return currentPath.value
    .split('/')
    .filter(Boolean)
    .map((name, index, arr) => ({
      name,
      path: '/' + arr.slice(0, index + 1).join('/')
    }));
});

const loadFiles = async () => {
  try {
    const response = await fetch(
      `/development/git/${props.projectId}/tree?path=${currentPath.value}&branch=main`,
      { headers: { 'Authorization': `Bearer ${getToken()}` } }
    );
    const result = await response.json();
    files.value = result.data;
  } catch (error) {
    console.error('加载文件失败:', error);
  }
};

const handleItemClick = async (item) => {
  if (item.type === 'folder') {
    currentPath.value = '/' + item.path;
    await loadFiles();
  } else {
    selectedFile.value = item;
    emit('file-selected', item);
    await loadFileContent(item.path);
  }
};

const loadFileContent = async (filePath) => {
  try {
    const response = await fetch(
      `/development/git/${props.projectId}/file?filePath=${encodeURIComponent(filePath)}&branch=main`,
      { headers: { 'Authorization': `Bearer ${getToken()}` } }
    );
    const result = await response.json();
    fileContent.value = result.data.content;
  } catch (error) {
    console.error('读取文件失败:', error);
  }
};

const saveFile = async () => {
  try {
    await fetch(
      `/development/git/${props.projectId}/file?filePath=${encodeURIComponent(selectedFile.value.path)}`,
      {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${getToken()}`
        },
        body: JSON.stringify({
          content: fileContent.value,
          message: `更新 ${selectedFile.value.name}`
        })
      }
    );
    alert('保存成功！');
  } catch (error) {
    console.error('保存失败:', error);
    alert('保存失败: ' + error.message);
  }
};

const navigateTo = (path) => {
  currentPath.value = path;
  loadFiles();
};

const formatFileSize = (bytes) => {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

onMounted(() => {
  loadFiles();
});
</script>
```

### 2. 提交历史组件

```vue
<template>
  <div class="commit-history">
    <div class="header">
      <h2>提交历史</h2>
      <select v-model="currentBranch" @change="loadCommits">
        <option v-for="branch in branches" :key="branch" :value="branch">
          {{ branch }}
        </option>
      </select>
    </div>

    <div class="commit-list">
      <div
        v-for="commit in commits"
        :key="commit.commitId"
        class="commit-item"
        @click="showCommitDetail(commit)"
      >
        <div class="commit-header">
          <strong>{{ commit.shortMessage }}</strong>
          <span class="time">{{ formatTime(commit.commitTime) }}</span>
        </div>
        <div class="commit-meta">
          <span class="author">{{ commit.authorName }}</span>
          <span class="id">{{ commit.commitId.substring(0, 7) }}</span>
        </div>
        <div class="commit-files" v-if="commit.changedFiles?.length">
          📝 {{ commit.changedFiles.length }} 个文件变更
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div class="pagination">
      <button @click="prevPage" :disabled="page <= 1">上一页</button>
      <span>第 {{ page }} 页</span>
      <button @click="nextPage">下一页</button>
    </div>

    <!-- 提交详情模态框 -->
    <div v-if="selectedCommit" class="modal" @click.self="closeDetail">
      <div class="modal-content">
        <h3>提交详情</h3>
        <p><strong>ID:</strong> {{ selectedCommit.commitId }}</p>
        <p><strong>作者:</strong> {{ selectedCommit.authorName }} &lt;{{ selectedCommit.authorEmail }}&gt;</p>
        <p><strong>时间:</strong> {{ formatTime(selectedCommit.commitTime) }}</p>
        <p><strong>消息:</strong></p>
        <pre>{{ selectedCommit.fullMessage }}</pre>

        <h4>变更文件:</h4>
        <ul>
          <li v-for="change in selectedCommit.changes" :key="change.newPath">
            {{ change.changeType }}: {{ change.oldPath || change.newPath }}
            <button
              v-if="change.changeType !== 'DELETE'"
              @click="viewFileVersion(change.newPath, selectedCommit.commitId)"
            >
              查看此版本
            </button>
          </li>
        </ul>

        <button @click="closeDetail">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';

const props = defineProps(['projectId']);

const branches = ref([]);
const currentBranch = ref('main');
const commits = ref([]);
const page = ref(1);
const pageSize = 20;
const selectedCommit = ref(null);

const loadBranches = async () => {
  try {
    const response = await fetch(
      `/development/git/${props.projectId}/branches`,
      { headers: { 'Authorization': `Bearer ${getToken()}` } }
    );
    const result = await response.json();
    branches.value = result.data;
  } catch (error) {
    console.error('加载分支失败:', error);
  }
};

const loadCommits = async () => {
  try {
    const response = await fetch(
      `/development/git/${props.projectId}/commits?branch=${currentBranch.value}&page=${page.value}&size=${pageSize}`,
      { headers: { 'Authorization': `Bearer ${getToken()}` } }
    );
    const result = await response.json();
    commits.value = result.data;
  } catch (error) {
    console.error('加载提交历史失败:', error);
  }
};

const showCommitDetail = async (commit) => {
  try {
    const response = await fetch(
      `/development/git/${props.projectId}/commit/${commit.commitId}`,
      { headers: { 'Authorization': `Bearer ${getToken()}` } }
    );
    const result = await response.json();
    selectedCommit.value = result.data;
  } catch (error) {
    console.error('加载提交详情失败:', error);
  }
};

const viewFileVersion = async (filePath, commitId) => {
  try {
    const response = await fetch(
      `/development/git/${props.projectId}/version?filePath=${encodeURIComponent(filePath)}&commitId=${commitId}`,
      { headers: { 'Authorization': `Bearer ${getToken()}` } }
    );
    const result = await response.json();

    // 在新窗口打开或显示在编辑器中
    window.open(`#/file-viewer?project=${props.projectId}&path=${encodeURIComponent(filePath)}&commit=${commitId}`);
  } catch (error) {
    console.error('加载文件版本失败:', error);
  }
};

const prevPage = () => {
  if (page.value > 1) {
    page.value--;
    loadCommits();
  }
};

const nextPage = () => {
  page.value++;
  loadCommits();
};

const closeDetail = () => {
  selectedCommit.value = null;
};

const formatTime = (isoString) => {
  return new Date(isoString).toLocaleString('zh-CN');
};

onMounted(() => {
  loadBranches();
  loadCommits();
});
</script>
```

### 3. 错误处理工具函数

```javascript
// utils/api.js

/**
 * 统一的API请求封装
 */
export async function apiRequest(url, options = {}) {
  const defaultHeaders = {
    'Authorization': `Bearer ${getToken()}`,
    'Content-Type': 'application/json'
  };

  try {
    const response = await fetch(url, {
      ...options,
      headers: {
        ...defaultHeaders,
        ...options.headers
      }
    });

    const result = await response.json();

    if (!response.ok || result.code !== 200) {
      throw new APIError(result.message, result.code, response.status);
    }

    return result.data;
  } catch (error) {
    if (error instanceof APIError) {
      throw error;
    }
    throw new APIError('网络错误，请检查连接', 500, 0);
  }
}

export class APIError extends Error {
  constructor(message, code, httpStatus) {
    super(message);
    this.name = 'APIError';
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

/**
 * Git操作专用错误处理
 */
export function handleGitError(error) {
  if (error.message.includes('不存在')) {
    return { type: 'not_found', message: error.message };
  }
  if (error.message.includes('仓库不存在')) {
    return { type: 'repo_not_found', message: '项目未初始化，请联系管理员' };
  }
  return { type: 'unknown', message: '操作失败: ' + error.message };
}

// Token管理
export function getToken() {
  return localStorage.getItem('auth_token') || sessionStorage.getItem('auth_token');
}
```

---

## 🔧 配置说明

### 后端配置 (application.yml)

```yaml
git:
  repositories:
    base-path: /data/bigdata-platform/git-repositories
```

### 前端配置

```javascript
// config/api.js
export const API_BASE_URL = process.env.VUE_APP_API_URL || 'http://localhost:8080';
export const GIT_API_PREFIX = '/development/git';
```

---

## 📊 性能建议

1. **分页加载**: 提交历史使用分页，避免一次加载过多数据
2. **缓存策略**: 文件树可以短期缓存，减少重复请求
3. **懒加载**: 大文件内容按需加载
4. **防抖处理**: 文件编辑器自动保存时使用防抖
5. **并发控制**: 避免同时编辑同一文件导致冲突

---

## ❓ 常见问题

### Q: 如何处理文件冲突？
A: 当前架构不支持多人同时编辑同一文件的冲突检测。建议通过分支管理实现协作。

### Q: 支持的文件大小限制？
A: 取决于服务器配置和JGit的限制，一般建议单个文件不超过100MB。

### Q: 如何备份Git仓库？
A: 直接复制整个仓库目录即可。也可以定期执行 `git clone --mirror` 到远程仓库。

### Q: 是否支持二进制文件？
A: 支持，但无法在界面中预览和对比差异。

---

## 📞 技术支持

如有问题，请联系开发团队或查看系统日志。

**最后更新**: 2026-05-08
