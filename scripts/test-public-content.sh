#!/bin/sh
set -eu

# 测试仅创建临时 Git 仓库和虚构数据，不使用真实账号、网络、数据库或项目提交。
checker="$(pwd)/scripts/check-public-content.sh"
fixture_dir=$(mktemp -d)
# 删除前固定并验证临时目录边界，不对未知或空路径执行递归清理。
fixture_parent=$(cd "$(dirname "$fixture_dir")" && pwd -P)
fixture_dir="$fixture_parent/$(basename "$fixture_dir")"
cleanup() {
    case "$fixture_dir" in
        "$fixture_parent"/tmp.*) rm -rf -- "$fixture_dir" ;;
        *) printf '%s\n' '拒绝清理超出测试临时目录的路径。' >&2 ;;
    esac
}
trap cleanup EXIT HUP INT TERM
cd "$fixture_dir"
git init --quiet
git config user.name 'Literal Studio Contributors'
git config user.email 'contributors@literal-studio.invalid'

expect_pass() {
    sh "$checker" "$@" >result.log 2>&1 || { cat result.log >&2; exit 1; }
}
expect_failure() {
    if sh "$checker" "$@" >result.log 2>&1; then
        printf '%s\n' '预期检查失败，但检查意外通过。' >&2
        exit 1
    fi
}

# 通用相对路径、标准容器目录和保留域名邮箱应通过，避免误伤正常工程配置。
printf '%s\n' './literal-studio' '/home/gradle/.gradle' 'demo@example.com' 'https://example.com/docs' >fixture.txt
git add fixture.txt
expect_pass --staged

# 虚构盘符绝对路径应失败，且日志只能报告文件名，不能复述敏感内容。
fake_drive=Q
printf '%s\n' "$fake_drive:\\SampleWorkspace\\literal-studio" >fixture.txt
git add fixture.txt
expect_failure --staged
grep -F 'fixture.txt' result.log >/dev/null
if grep -F 'SampleWorkspace' result.log >/dev/null; then exit 1; fi

# 提交说明中出现虚构个人邮箱应失败，验证文件之外的公开文本也会被检查。
printf '%s\n' './literal-studio' >fixture.txt
git add fixture.txt
printf '%s%s%s\n' 'fixture' '@' 'mail.test' >message.txt
expect_failure --message message.txt

# 允许域名后的额外后缀不能绕过完整邮箱校验，避免只匹配前半段保留域名。
printf '%s%s%s\n' 'fixture' '@' 'example.com.test' >fixture.txt
git add fixture.txt
expect_failure --staged

# macOS 和 Linux 用户目录都应被阻止；字符串拆开构造，避免测试源码自身触发检查。
for base in Users home; do
    printf '/%s/%s/project\n' "$base" 'fixture-user' >fixture.txt
    git add fixture.txt
    expect_failure --staged
done

# 不在允许列表中的虚构邮箱应失败，防止仅靠改名遗漏个人联系方式。
printf '%s%s%s\n' 'fixture' '@' 'mail.test' >fixture.txt
git add fixture.txt
expect_failure --staged

# 内容正常但提交身份不符合项目约定时也应失败，不允许沿用全局 Git 邮箱。
printf '%s\n' './literal-studio' >fixture.txt
git add fixture.txt
GIT_AUTHOR_NAME='Fixture Author' sh "$checker" --staged >result.log 2>&1 && exit 1

# 推送检查应遍历全部可达提交，即使最新提交匿名也不能掩盖旧提交的身份。
git -c user.name='Fixture Author' commit --quiet -m '创建虚构历史测试夹具'
printf '%s\n' './core' >fixture.txt
git add fixture.txt
git commit --quiet -m '添加通用身份测试提交'
expect_failure --revision HEAD

# 所有身份匿名但旧快照曾含绝对路径时仍应失败，不能靠后续删除掩盖历史。
printf '%s\n' "$fake_drive:\\SampleWorkspace\\literal-studio" >fixture.txt
git add fixture.txt
old_tree=$(git write-tree)
old_oid=$(printf '%s\n' '创建虚构路径历史夹具' | git commit-tree "$old_tree")
printf '%s\n' './literal-studio' >fixture.txt
git add fixture.txt
new_tree=$(git write-tree)
new_oid=$(printf '%s\n' '清理当前快照' | git commit-tree "$new_tree" -p "$old_oid")
expect_failure --revision "$new_oid"

# 无历史父节点的匿名提交应通过，模拟已经清理后的初始历史。
clean_oid=$(printf '%s\n' '创建干净历史测试夹具' | git commit-tree "$(git write-tree)")
expect_pass --revision "$clean_oid"
printf '%s\n' '公开内容检查测试通过。'
