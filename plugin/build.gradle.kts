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
    // 仅验证真实 Swing 布局边界，不启动 IDE、解释器或网络，不将测试框架打入插件。
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    intellijPlatform {
        // 使用固定 PyCharm SDK 作为构建基线；仅依赖通用平台，暂不依赖 Python/JS 专用插件。
        pycharm(providers.gradleProperty("platformVersion"))
        // 二进制验证工具仅在容器构建期使用，不进入插件运行依赖。
        pluginVerifier()
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

intellijPlatform {
    // 功能集中在工具窗口，没有设置页；无需为了生成选项索引在容器启动 IDE。
    buildSearchableOptions = false

    pluginVerification {
        ides {
            // 只验证当前固定 PyCharm 基线，复用已有 SDK；不据此扩大兼容产品或版本。
            current()
        }
    }

    pluginConfiguration {
        name = "Literal Studio"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            // 首阶段只声明当前 262 分支；其他产品和版本须实测后再扩大范围。
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
        changeNotes = "Add local JSON, JS/TS literal and Python dict/list validation, formatting and conversion with a split preview tool window."
    }
}
