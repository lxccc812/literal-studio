# 固定 Gradle 和 JDK 版本，与 PyCharm 2026.2 的 Java 25 平台要求保持一致。
FROM gradle:9.3.0-jdk25@sha256:2e614dffcc0266a28f70b323a3044472dfa75a7f6873d94a759a4fb785dc78da

WORKDIR /workspace
ENV GRADLE_USER_HOME=/home/gradle/.gradle

# 源码由 Compose 挂载，不复制进镜像；构建输出直接保存在项目目录中。
ENTRYPOINT ["sh", "/workspace/scripts/container-gradle.sh"]
CMD ["help"]
