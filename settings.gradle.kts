pluginManagement {
    repositories {
        // 腾讯云 Maven 镜像：优先走镜像，避免 Maven Central 限流(429)
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // 阿里云 Gradle Plugin Portal 镜像：国内直连 plugins.gradle.org 不稳定，会导致仅发布在
        // Plugin Portal 的插件（如 io.github.philkes.* 系列、ktfmt）实现构件解析失败。
        // 放在 gradlePluginPortal() 之前，优先从国内镜像获取。
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 腾讯云 Maven 镜像：优先走镜像，避免 Maven Central 限流(429)
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "memoX"
include(":app")
