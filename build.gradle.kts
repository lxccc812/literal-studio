plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("org.jetbrains.intellij.platform") version "2.19.0" apply false
}

allprojects {
    group = "io.github.literalstudio"
    version = providers.gradleProperty("pluginVersion").get()
}

// 汇总任务只构建与检查本项目，不接入远程发布，也不启动图形 IDE。
tasks.register("check") {
    group = "verification"
    description = "检查核心模块与插件模块"
    dependsOn(":core:check", ":plugin:check")
}

tasks.register("build") {
    group = "build"
    description = "检查项目并构建本地插件安装包"
    dependsOn("check", ":plugin:buildPlugin")
}
