plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.dshmobile.shell"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.dshmobile.shell"
    minSdk = 26
    // targetSdk 34: Android 15+ forbids exec of app-data ELF for targetSdk 35+
    // (the embedded engine, bash, and every child command would need linker64
    // wrappers); 34 keeps native exec working on Android 15/16 devices.
    targetSdk = 34
    // Android 16 平板深度定制版（arm64 快照 + 桌面窗口模式适配）。
    versionCode = 11
    versionName = "0.11.0-a16-tablet"
  }

  androidResources {
    // snapshot.tar.xz is already xz-compressed; double-compressing it breaks openFd.
    noCompress += "xz"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // 开箱即用：release 使用 debug 签名，APK 可直接安装到平板测试
      // （正式发布时替换为自有 keystore）。
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  lint {
    // 离线环境无 lint-gradle 依赖缓存（国内网络）；lint 非发布关键路径。
    checkReleaseBuilds = false
    abortOnError = false
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

// 运行时快照来自 GitHub Releases（大文件不入库）；缺失时构建失败并给出获取指引。
tasks.whenTaskAdded {
  if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
    doFirst {
      val snap = file("src/main/assets/snapshot.tar.xz")
      if (!snap.exists()) {
        throw GradleException(
          "缺少运行时快照 assets/snapshot.tar.xz —— " +
            "从 GitHub Releases 下载 snapshot-x86_64.tar.xz 后放到 app/src/main/assets/snapshot.tar.xz，" +
            "或按 scripts/make-snapshot.sh 在 Termux 设备自打后拉取（见 README.md）",
        )
      }
    }
  }
}

dependencies {
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("org.apache.commons:commons-compress:1.27.1")
  implementation("org.tukaani:xz:1.10")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
}