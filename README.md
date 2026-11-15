# 轻页 PDF · QPDF 2.2

基于第一版源码修改的 Android 离线 PDF 页面提取工具。支持指定页面、连续页段、混合范围、奇数页、偶数页、全部页。自动排序和去重，始终按原文件顺序生成新的 PDF，原文件保持不变。

## 2.2 预览与高清翻页

读取文件后，点击“预览页面 / 跳转页码”进入缩略图页面。输入页码，停顿 0.6 秒后自动跳转并用绿色边框高亮；也可以点击“跳转”或键盘“前往”。无效或越界页码有明确提示。页码按原 PDF 的物理页面顺序从 1 开始。

- 修复已高亮页面再次搜索不滚回的缺陷；使用 RecyclerView 按目标位置定位，键盘引起布局变化时保留跳转目标。
- 双击缩略图进入高清，重新从 PDF 渲染；左右滑动切换相邻页，也可点击上一页/下一页。返回后缩略图定位到当前页。
- 高清图长边不超过 3072 像素、总像素不超过 400 万；只预加载当前页及前后各一页，高清缓存上限 48 MiB。该预算不包含 PDF 解码器等内存。
- 两列缩略图，滚动复用视图。只生成可见页面，跳页时替换尚未执行的旧任务，并优先渲染目标页。
- 单张缩略图不超过 320×448 像素，图片 LRU 缓存上限 12 MiB；这是图片缓存预算，并非整个渲染进程的内存上限。
- 预览 Activity 位于独立 `:preview` 进程，文件读取、复制和渲染在后台线程运行。关闭预览后结束该进程，提取主界面仍可继续使用。
- 普通文件使用 Android 系统 PdfRenderer；有密码或开启修复模式时，用已有 QPDF 核心按需准备当前单页后渲染，避免生成整份解密副本。此模式首次预览可能较慢，需要满足原有重组权限。
- 优先直接读取可随机访问的文件；如主提取流程已准备本地缓存，预览会复用缓存。无法随机访问的提供器才需准备临时副本。
- 点击缩略图只改变定位高亮，不改变主界面的待提取页码。
- 包名、签名与已交付 2.0 / 2.1 相同，版本号升为 2.2.0（22），可直接覆盖升级。

## 安装与使用

- Android 10 及以上、ARM64（arm64-v8a）手机。
- 应用 ID 为 `cn.paperslice.qpdf`，可与第一版 `cn.paperslice.app` 并存。原签名私钥不在上传源码内，因此使用新的包名和开发签名，不要求卸载旧版。
- 选择 PDF，输入如 `1,5,10-20`，点击提取，再选择“保存到…”。应用内结果也可打开或分享。
- 密码不会写入设置或通知。需要所有者权限的文件须输入所有者密码。
- 默认快速失败，不自动进行耗时的损坏索引恢复。文件索引异常时，可开启“尝试修复损坏文件”，点击“重新读取文件”。
- 提取过程显示阶段、已用时间及写入进度；前处理阶段没有伪造的百分比。

## 沿用的 QPDF 大文件优化

1. 删除 PDFBox-Android 全量解析和跨文档共享整份资源字典的实现，改为 QPDF 12.4.2 原生 C++ 库，通过 JNI 调用。
2. 使用 QPDF 自带的 `--empty --pages ... --remove-unreferenced-resources=yes --stream-data=preserve` 流程，清理选中页资源字典里实际未使用的图片、字体等引用，避免把未选页面的图片一起复制。
3. 保留原始图片流，默认不降采样、不重编码图片、不把页面转成位图，不启用耗时的最大压缩或线性化。
4. 对可随机访问的系统文件描述符通过 `/proc/self/fd` 直接读取；只有无法随机访问时才生成一次本地副本，使用 1 MiB 缓冲。
5. PDF 工作运行于独立 `:pdf` 进程，正常线程优先级。界面通过 Messenger 读取状态。取消原生提取会结束工作进程，主界面仍在；后续启动清理未完成文件。
6. 先写 `.part`，同步落盘、校验输出页数后再改为 `.pdf`。提取失败保留上一次成功结果。系统目标保存失败会尝试删除不完整文件，无法删除时明确提示。

QPDF 的单个提取任务并非多核线性加速；本次优先减少多余解析、复制和压缩。不会为了显示 CPU 满载而重复处理文件。

## 边界

- 新 PDF 不加密；原数字签名不会保留。书签、文档级表单、跨页链接等不保证完整保留，请在重要文档提取后核对。
- 文件体积不一定按页数等比例缩小。如果选中页确实依赖大图、共享字体或一个覆盖多页的大资源，必要数据仍会保留。此版本不做有损图片压缩。
- 内存与 PDF 对象数量、压缩内容复杂度相关，不能仅凭文件字节数给出固定内存上限。
- 对云端文档提供器、慢速 SD 卡或需修复的 PDF，读取/复制阶段仍可能较慢。推荐先将文件下载到手机本地存储。
- APK 已完成构建与静态检查，原生核心和 JNI 已测试；2.0 核心沿用已有测试结果，2.2 增加 Robolectric 界面交互测试、高清尺寸/队列测试与构建检查（界面测试使用替身渲染器）；本次没有 Android 真机或模拟器实测，不将桌面结果当作手机性能承诺。

## 构建

环境：JDK 17，Gradle 8.9（随附 wrapper），Android SDK 35、Build Tools 35.0.0，Python 3、CMake 3.16+、C/C++ 编译器、Android NDK r27c 或更新版。

原生依赖源码已固定并随包提供：QPDF 12.4.2、libjpeg-turbo 3.1.0。Android 使用 NDK 系统 zlib 和静态 libc++。应用使用 AndroidX Core 1.13.1、RecyclerView 1.3.2、ViewPager2 1.1.0、Activity 1.9.3、Kotlin 1.9.24；界面测试使用 Robolectric 4.14.1。

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c
python3 tools/build_native.py arm64-v8a
printf 'sdk.dir=/path/to/android-sdk\n' > local.properties
./gradlew assembleDebug
```

随源码附带已构建的 `app/src/main/jniLibs/arm64-v8a/libpaperslice.so`，仅修改 Kotlin 界面时可以跳过原生编译。输出 APK 位于 `app/build-qpdf/outputs/apk/debug/`。

`development/debug.keystore` 是明确公开的测试签名，密码与别名使用 Android 开发默认值（密码 `android`，别名 `androiddebugkey`）。保留此文件可继续安装测试版更新；正式发布应自行创建并保管私钥、配置独立发布签名，不能使用公开测试密钥。

## 测试

```sh
# 需要 JDK 17、CMake、C++17 编译器和 zlib 开发文件
python3 tools/build_native.py host
python3 -m pip install pymupdf reportlab
python3 tools/test_native.py
./gradlew testDebugUnitTest lintDebug
# 仅验证界面交互、预览与页码逻辑，不需构建主机原生库：
./gradlew testDebugUnitTest --tests cn.paperslice.app.PreviewActivityTest --tests cn.paperslice.app.HdPreviewTest --tests cn.paperslice.app.PreviewNavigationTest --tests cn.paperslice.app.PageSelectionTest
```

大文件测试自动生成约 1.3 GB 的临时样本，需留出至少 2 GB 空间。每个原生测试子进程限制 256 MiB 地址空间；这不代表 Android 进程峰值内存。

`TEST-REPORT.md` 和 `verification/` 记录本轮验证；`verification/2.0/` 和 `verification/2.1/` 保留历史结果。`test-work/`、`native-build/`、SDK、NDK、Gradle 缓存不随源码包分发。

## 许可

应用沿用第一版 Apache-2.0 许可。QPDF 与 libjpeg-turbo 原始版权和许可保留在 `third_party/`。APK `assets/` 中包含 QPDF、IJG/libjpeg-turbo、Android NDK 的许可与声明。本软件部分基于 Independent JPEG Group 的工作。
