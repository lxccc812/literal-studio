plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // 核心编译需要标准库，运行时由宿主 IDE 提供，避免插件携带重复版本。
    compileOnly(kotlin("stdlib"))

    // 解析与转换测试只运行纯 JVM 代码，不依赖 IDE、数据库、网络或外部解释器。
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
