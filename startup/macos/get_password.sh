#!/usr/bin/env bash
#
# Prints the preset password mapped to the user who runs this script.
# Purpose: return the matching preset password for the current login user.
#
set -euo pipefail

# ============================================================
# Config area: maintain the "user -> password" mappings here.
# To add or remove users later, edit only this block; the
# logic below does not need to change.
# ============================================================

# User/password mappings (format: user:password)
USER_PASSWORDS=(
    "root:password123"
    "admin:abc456"
)

# ============================================================
# Get the current user
# ============================================================
CURRENT_USER="$(whoami)"

# Placeholder value for the no-match case, so that the output is
# never mistaken for a real password.
NOT_FOUND_PROMPT="unknown_user_${CURRENT_USER}"

# Iterate over the mapping table and find a match
matched_password=""
for entry in "${USER_PASSWORDS[@]}"; do
    user="${entry%%:*}"
    if [[ "${user}" == "${CURRENT_USER}" ]]; then
        matched_password="${entry#*:}"
        break
    fi
done

# If nothing matched, print a notice and exit
if [[ -z "${matched_password}" ]]; then
    echo "Current user '${CURRENT_USER}' has no matching password in the mapping table" >&2
    # To still print a placeholder value on no match, uncomment the next line
    # echo "${NOT_FOUND_PROMPT}"
    exit 1
fi

# Print the matched password
echo "${matched_password}"