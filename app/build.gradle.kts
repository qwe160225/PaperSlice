plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// Separate output tree avoids stale artifacts from the first PDFBox build.
layout.buildDirectory.set(layout.projectDirectory.dir("build-qpdf"))
android {
    namespace = "cn.paperslice.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.paperslice.qpdf"
        minSdk = 29
        targetSdk = 35
        versionCode = 20
        versionName = "2.0.0"
    }
    signingConfigs { getByName("debug") { storeFile = rootProject.file("development/debug.keystore") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "256m"
            it.systemProperty("java.library.path", rootProject.file("native-build/host/app").absolutePath)
            it.systemProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))
            if (project.hasProperty("largePdf")) it.systemProperty("largePdf", "true")
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES") }
}
dependencies {
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
