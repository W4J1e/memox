buildscript {
    repositories {
        // 腾讯云 Maven 镜像：优先走镜像，避免 Maven Central 限流(429)；覆盖 root :classpath 解析
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
        classpath("org.apache.commons:commons-configuration2:2.4")
    }
}

plugins {
    id("com.google.devtools.ksp") version "1.9.0-1.0.13" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory.asFile)
}