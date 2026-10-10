#!/bin/sh
set -eu

# Windows 不保证脚本可执行位，因此通过 sh 启动 Wrapper，统一容器构建入口。
if [ ! -f /workspace/gradle/wrapper/gradle-wrapper.jar ]; then
    printf '%s\n' '缺少 Gradle Wrapper，请先完成项目初始化。' >&2
    exit 1
fi

# 默认执行帮助任务；每次使用短生命周期容器，不留下常驻 Gradle daemon。
if [ "$#" -eq 0 ]; then
    set -- help
fi

exec sh /workspace/gradlew --no-daemon --console=plain "$@"
