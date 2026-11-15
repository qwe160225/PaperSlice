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
        versionCode = 22
        versionName = "2.2.0"
    }
    signingConfigs { getByName("debug") { storeFile = rootProject.file("development/debug.keystore") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            val testTask = it
            it.maxHeapSize = "1024m"
            it.systemProperty("robolectric.dependency.repo.url", System.getProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2"))
            it.systemProperty("maven.repo.local", rootProject.file(".gradle/robolectric-cache").absolutePath)
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
                System.getProperty(key)?.let { value -> testTask.systemProperty(key, value) }
            }
            it.systemProperty("java.library.path", rootProject.file("native-build/host/app").absolutePath)
            it.systemProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))
            if (project.hasProperty("largePdf")) it.systemProperty("largePdf", "true")
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES") }
}
dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.activity:activity:1.9.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
