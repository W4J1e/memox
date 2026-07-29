pluginManagement {
    repositories {
        // 阿里云 Gradle Plugin Portal 镜像：放在最前，优先使用。
        // 仅发布在 Plugin Portal 的插件（io.github.philkes.* 系列、ktfmt 等）实现构件，
        // 腾讯云 maven-public 镜像不代理 Portal；且 io.github.philkes 组在 Maven Central 存在
        // （含其他无关构件），腾讯云 Nexus 会返回 200/元数据"认领"模块但实际无 jar，导致 Gradle
        // 锁定腾讯云、不回退到其它仓库而失败。阿里云该镜像代理 Portal 且国内稳定，放最前可优先命中。
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }
        // 腾讯云 Maven 镜像：Maven Central 构件走镜像，避免限流(429)
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
