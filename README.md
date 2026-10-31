# 轻页 PDF · QPDF（PaperSlice）2.0.0

Offline Android PDF page extractor powered by QPDF — keep only the pages you need, on-device.

基于第一版源码改写的 Android **离线** PDF 页面提取工具。在手机本地选择 PDF、指定要保留的页面，生成新的 PDF；原文件不变，不上传网络。

> 预编译 arm64 APK（`PaperSlice-QPDF-2.0.0-arm64.apk`，版本 2.0.0）见本仓库 **Releases**。

## 功能

- 按页码提取：单页、连续页段、混合范围（如 `1,5,10-20`），支持中文标点分隔
- 快捷选项：全部 / 奇数页 / 偶数页；自动排序、去重，输出始终按原文件页序
- 加密 PDF：可输入所有者/打开密码（不写入设置或通知）；重组受限时需所有者密码
- 可选“尝试修复损坏文件”（默认快速失败，不自动做耗时修复）
- 独立 `:pdf` 工作进程 + 前台通知；可取消任务；先写 `.part` 再校验后改为 `.pdf`
- 尽量保留原图流：默认不降采样、不重编码、不把页面栅格化
- 用 QPDF 清理未引用资源，减少把未选页面的图片一并复制进结果

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 应用 | Kotlin、AndroidX Core 1.13.1、minSdk 29（Android 10+）、target/compileSdk 35 |
| 包名 | `cn.paperslice.qpdf`（可与第一版 `cn.paperslice.app` 并存） |
| 构建 | Gradle 8.9（wrapper）、Android Gradle Plugin 8.7.3、Kotlin 1.9.24、JDK 17 |
| 原生 | C++17、CMake 3.16+、JNI；内置 `libpaperslice.so`（arm64-v8a） |
| 依赖源码 | QPDF 12.4.2、libjpeg-turbo 3.1.0（见 `third_party/`）；Android NDK zlib / 静态 libc++ |

## 目录结构

```
PaperSlice/
├── app/                     # Android 应用模块
│   └── src/main/
│       ├── java/cn/paperslice/app/   # Kotlin UI / Service / JNI 封装
│       ├── jniLibs/arm64-v8a/        # 预构建 libpaperslice.so
│       ├── assets/                   # 第三方许可与声明
│       └── res/
├── native/                  # C++ 核心、JNI、CMakeLists
├── tools/                   # build_native.py / test_native.py
├── third_party/             # QPDF、libjpeg-turbo 源码
├── development/             # 公开 debug.keystore（仅测试签名）
├── verification/            # 本轮构建与测试记录
├── TEST-REPORT.md           # 验证报告
├── gradle/ / gradlew*       # Gradle Wrapper
└── LICENSE                  # Apache-2.0
```

## 环境要求

- JDK 17
- Android SDK 35、Build Tools 35.0.0
- （可选，重编原生库时）Android NDK r27c+、Python 3、CMake 3.16+、C/C++ 编译器
- 设备：Android 10+，**ARM64（arm64-v8a）**

## 构建

源码已附带 `app/src/main/jniLibs/arm64-v8a/libpaperslice.so`，仅改 Kotlin 界面时可跳过原生编译。

```sh
# 可选：重编原生库
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c
python3 tools/build_native.py arm64-v8a

# 配置 SDK 路径后打包
printf 'sdk.dir=/path/to/android-sdk\n' > local.properties
./gradlew assembleDebug
```

输出 APK：`app/build-qpdf/outputs/apk/debug/`。

`development/debug.keystore` 为**明确公开的测试签名**（Android 默认：密码 `android`，别名 `androiddebugkey`）。正式发布请自行创建并保管私钥，勿使用此公开密钥。

## 测试

```sh
python3 tools/build_native.py host
python3 -m pip install pymupdf reportlab
python3 tools/test_native.py
./gradlew testDebugUnitTest lintDebug
```

大文件测试会生成约 1.3 GB 临时样本，请预留至少 2 GB 空间。详见 `TEST-REPORT.md` 与 `verification/`。

## 使用说明（简要）

1. 安装 APK 或自行构建后安装。
2. 选择 PDF → 输入页码（或点全部/奇数页/偶数页）→ 提取 →「保存到…」/打开/分享。
3. 有密码时展开密码栏输入；索引异常可勾选修复后「重新读取文件」。

## 边界与限制

- 新 PDF **不加密**；原数字签名不保留。书签、文档级表单、跨页链接等不保证完整保留。
- 体积不一定按页数等比缩小；选中页依赖的大图/共享字体仍会保留。本版不做有损图片压缩。
- 云端文档提供器、慢速存储或需修复的 PDF，读取阶段可能较慢；建议先下载到本地存储。

## 许可

应用采用 **Apache License 2.0**（见根目录 `LICENSE`）。QPDF 与 libjpeg-turbo 等原始版权与许可保留在 `third_party/` 及 APK `assets/`。本软件部分基于 Independent JPEG Group 的工作。
