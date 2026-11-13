import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkPath = providers.gradleProperty("weartakPluginSdkAar").orNull
    ?: throw GradleException("Set -PweartakPluginSdkAar to the verified combined WearTAK SDK AAR.")
val sdkFile = file(sdkPath)
if (!File(sdkPath).isAbsolute || !sdkFile.isFile) {
    throw GradleException("weartakPluginSdkAar must be an absolute path to an existing file.")
}
val sdkDigest = MessageDigest.getInstance("SHA-256")
sdkFile.inputStream().use { input ->
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        sdkDigest.update(buffer, 0, count)
    }
}
val sdkHash = sdkDigest.digest().joinToString("") { "%02x".format(it) }
val approvedSdkHashes = setOf(
    "6ec84b18e1c8ac6b4be727083c4ea7a572150b521384270212434c239e5a1e0b",
    "8f911b81ac85e691ad182097fe0ef0f00ffa0d2da3442d3d2f03c5ec2529184f",
)
if (sdkHash !in approvedSdkHashes) {
    throw GradleException("WearTAK SDK AAR does not match the pinned combined SDK hash.")
}

android {
    namespace = "com.weartak.plugin.ditto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weartak.plugin.ditto"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").java.srcDir("src/sdkAdapter/java")
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

val compileDittoTestFake by tasks.registering(JavaCompile::class) {
    source(fileTree("src/sdkAdapterTestFake/java") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/dittoTestFake"))
    options.release.set(17)
}

val dittoTestFakeJar by tasks.registering(Jar::class) {
    from(compileDittoTestFake)
    archiveFileName.set("synthetic-ditto-test-fake.jar")
    destinationDirectory.set(layout.buildDirectory.dir("sdkAdapterTestFake"))
}

dependencies {
    implementation(files(sdkFile))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testCompileOnly("live.ditto:ditto:4.14.6")
    testRuntimeOnly(files(dittoTestFakeJar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
}
