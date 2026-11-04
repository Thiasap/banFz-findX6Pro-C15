#!/bin/sh
# 推送到 github.com/yooma1/qufz（部署密钥专用，走 443）
# 前置：qufz 仓库 Settings → Deploy keys 已加入 /root/.ssh/qufz_deploy.pub 并勾选 Allow write access
set -e
cd "$(dirname "$0")"

export GIT_SSH_COMMAND="ssh -i /root/.ssh/qufz_deploy -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o ConnectTimeout=20"

echo "== 权限预检"
if ! git ls-remote qufz >/dev/null 2>&1; then
  echo "读取失败：部署密钥未添加到 yooma1/qufz，或未勾选 Allow write access"
  echo "需要添加的公钥："
  cat /root/.ssh/qufz_deploy.pub
  exit 1
fi

echo "== 推送"
git push qufz HEAD:main

echo "== 复核"
git ls-remote qufz
