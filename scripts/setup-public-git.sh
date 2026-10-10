#!/bin/sh
set -eu

# 仅改变当前仓库的身份和 hook 路径，不覆盖其他仓库或全局 Git 设置。
cd "$(git rev-parse --show-toplevel)"
git config --local user.name 'Literal Studio Contributors'
git config --local user.email 'contributors@literal-studio.invalid'
git config --local core.hooksPath .githooks
printf '%s\n' '已为当前仓库启用通用项目提交身份及公开内容检查。'
