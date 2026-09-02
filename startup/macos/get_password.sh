#!/usr/bin/env bash
#
# 获取当前运行脚本的用户，并根据用户名输出对应的密码
# 用途：根据当前登录用户，返回与之匹配的预设密码
#
set -euo pipefail

# ============================================================
# 配置区：在此处维护「用户名 -> 密码」的映射关系
# 后续如需增删用户，只改这里即可，无需改动下方逻辑
# ============================================================

# 定义用户密码映射（格式：用户名:密码）
USER_PASSWORDS=(
    "root:password123"
    "admin:abc456"
)

# ============================================================
# 获取当前用户
# ============================================================
CURRENT_USER="$(whoami)"

# 设置一个未匹配到的默认提示，避免输出被直接当成真实密码
NOT_FOUND_PROMPT="unknown_user_${CURRENT_USER}"

# 遍历映射表并匹配
matched_password=""
for entry in "${USER_PASSWORDS[@]}"; do
    user="${entry%%:*}"
    if [[ "${user}" == "${CURRENT_USER}" ]]; then
        matched_password="${entry#*:}"
        break
    fi
done

# 若未匹配到，给出提示并退出
if [[ -z "${matched_password}" ]]; then
    echo "当前用户 '${CURRENT_USER}' 未在映射表中找到对应密码" >&2
    # 如需在未匹配时仍输出占位值，可将下面一行取消注释
    # echo "${NOT_FOUND_PROMPT}"
    exit 1
fi

# 输出匹配到的密码
echo "${matched_password}"