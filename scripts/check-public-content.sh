#!/bin/sh
set -eu

# 默认检查完整暂存区，避免已跟踪但未改动的敏感内容被漏掉；推送时检查全部历史。
mode=${1:---staged}
case "$mode" in
    --staged)
        [ "$#" -le 1 ] || exit 2
        ;;
    --message)
        [ "$#" -eq 2 ] && [ -f "$2" ] || exit 2
        ;;
    --revision)
        [ "$#" -eq 2 ] || exit 2
        git rev-parse --verify "$2^{commit}" >/dev/null
        ;;
    *)
        printf '%s\n' '用法：sh scripts/check-public-content.sh [--staged | --message FILE | --revision REV]' >&2
        exit 2
        ;;
esac

# 临时文件不进入仓库；报告只显示文件名，避免把检出的个人信息再次写入日志。
scan_dir=$(mktemp -d)
trap 'rm -f "$scan_dir/content" "$scan_dir/identities" "$scan_dir/trees"; rmdir "$scan_dir"' EXIT HUP INT TERM
if [ "$mode" != --revision ]; then
    status=0
    git grep --cached -n -I -e . -- . >"$scan_dir/content" || status=$?
    { git var GIT_AUTHOR_IDENT; git var GIT_COMMITTER_IDENT; } |
        sed 's/>.*/>/' >"$scan_dir/identities"
    if [ "$mode" = --message ]; then
        # 提交说明也会公开，必须在提交创建前与文件内容使用相同的规则检查。
        while IFS= read -r message_line || [ -n "$message_line" ]; do
            printf 'Git-commit-message:1:%s\n' "$message_line" >>"$scan_dir/content"
        done <"$2"
    fi
else
    # 对历史快照去重后逐个扫描，防止最新文件干净却仍保留较早的敏感内容。
    git log --format='%T' "$2" | sort -u >"$scan_dir/trees"
    : >"$scan_dir/content"
    while IFS= read -r tree; do
        status=0
        git grep -n -I -e . "$tree" -- . >>"$scan_dir/content" || status=$?
        [ "$status" -le 1 ] || exit "$status"
    done <"$scan_dir/trees"
    git log --format='%B' "$2" | while IFS= read -r message_line; do
        printf 'Git-commit-message:1:%s\n' "$message_line"
    done >>"$scan_dir/content"
    # 所有可达提交都必须使用项目身份，不能仅检查最新一次提交的作者。
    git log --format='%an <%ae>%n%cn <%ce>' "$2" >"$scan_dir/identities"
fi
if [ "$mode" != --revision ]; then [ "$status" -le 1 ] || exit "$status"; fi

# 标准容器目录和明确虚构的邮箱不属于个人信息；其他命中必须人工清理。
awk '
    {
        line = $0
        file = line
        sub(/:[0-9]+:.*/, "", file)
        sub(/^.*:[0-9]+:/, "", line)
        gsub(/\/home\/gradle\//, "", line)
        # 盘符前不能是单词或变量字符，避免把 URL 的末尾字符当成盘符。
        drive = "(^|[^[:alnum:]_$])[[:alpha:]]:" "[/\\\\]"
        mac_user = "/" "Users" "/[^/[:space:]]+"
        linux_user = "/" "home" "/[^/[:space:]]+"
        email = "[[:alnum:]_.%+-]+@[[:alnum:].-]+[.][[:alpha:]]{2,}"
        suspect = line ~ drive || line ~ mac_user || line ~ linux_user
        remaining = line
        while (match(remaining, email)) {
            address = substr(remaining, RSTART, RLENGTH)
            if (address !~ /[.]invalid$/ && address !~ /@example[.](com|org|net)$/) suspect = 1
            remaining = substr(remaining, RSTART + RLENGTH)
        }
        if (suspect) {
            if (!reported[file]++) print "公开内容检查失败：" file > "/dev/stderr"
            failed = 1
        }
    }
    END { exit failed ? 1 : 0 }
' "$scan_dir/content"

# 统一项目身份可防止本机全局 Git 邮箱被带入公开提交；不修改全局配置。
expected='Literal Studio Contributors <contributors@literal-studio.invalid>'
if grep -Fvx "$expected" "$scan_dir/identities" >/dev/null; then
    printf '%s\n' '提交身份检查失败：请运行 sh scripts/setup-public-git.sh，并检查历史元数据。' >&2
    exit 1
fi
printf '%s\n' '公开内容与提交身份检查通过。'
