plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        // 使用固定 PyCharm SDK 作为构建基线；仅依赖通用平台，暂不依赖 Python/JS 专用插件。
        pycharm(providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    // 当前骨架没有设置页，关闭选项索引生成，避免仅为打包在容器中启动 IDE。
    buildSearchableOptions = false

    pluginConfiguration {
        name = "Literal Studio"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            // 首阶段只声明当前 262 分支；其他产品和版本须实测后再扩大范围。
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
        changeNotes = "Initial project scaffold; formatting and conversion are not implemented yet."
    }
}
