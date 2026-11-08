# 构建说明

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17 或 21（Gradle 8.10 不支持 Java 25） |
| Android SDK | compileSdk 35、build-tools 34+ |
| Gradle | 随 `gradlew` 自动下载（8.10.x） |

## 首次构建

```bash
# 1. 设置 SDK 路径（或直接放在 local.properties）
export ANDROID_HOME=/path/to/Android/Sdk

# 2. 调试包
./gradlew assembleDebug

# 3. 正式包（需要签名配置，见下）
./gradlew assembleRelease
```

Windows：

```powershell
$env:ANDROID_HOME = "D:\Users\<you>\AppData\Local\Android\Sdk"
.\gradlew.bat assembleRelease
```

## 签名配置

**不要把 keystore 提交到仓库。** 推荐本地用 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=../keystore/release.jks
storePassword=******
keyAlias=filee
keyPassword=******
```

`app/build.gradle.kts`：

```kotlin
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

## 生成 keystore

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias filee \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, O=Your Org, C=CN"
```

> keystore 一旦用于发布就不能丢：丢掉后已安装用户无法覆盖升级。

## CI（可选）

见 [ci-example.yml](ci-example.yml)。把该文件改名为
`.github/workflows/android.yml`，并在仓库 Secrets 里配置：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` 的结果 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `filee` |
| `KEY_PASSWORD` | key 口令 |

推送 tag 即自动构建并上传 APK 到 Release。

## 常见问题

**`Unsupported class file major version`** —— JDK 版本过新，换 17/21。

**`SDK location not found`** —— 设置 `ANDROID_HOME` 或写 `local.properties` 的 `sdk.dir`。

**构建后 APK 体积很大** —— 确认用的是 `assembleRelease` 且 `isMinifyEnabled = true`。
