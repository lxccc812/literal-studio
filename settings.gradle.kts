pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "literal-studio"

// 核心模块独立于 IDE，便于在容器内测试解析和转换；插件模块负责平台接入。
include(":core", ":plugin")
