import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import org.gradle.BuildAdapter
import org.gradle.BuildResult

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningVariableNames = listOf(
    "WEARTAK_RELEASE_KEYSTORE_B64",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)
val releaseSigningVariables = releaseSigningVariableNames.associateWith {
    providers.environmentVariable(it)
}
val presentReleaseSigningVariables = releaseSigningVariables.filterValues { it.isPresent }
if (presentReleaseSigningVariables.isNotEmpty() &&
    presentReleaseSigningVariables.size != releaseSigningVariableNames.size
) {
    throw GradleException("Incomplete release signing inputs; configure all required CI variables.")
}
val releaseSigningConfigured = presentReleaseSigningVariables.size == releaseSigningVariableNames.size
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf(
        "assembleRelease",
        "packageRelease",
        "bundleRelease",
        "installRelease",
        "verifyReleaseSigning",
        "assemble",
        "build",
    )
}
val releaseVerificationRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "verifyReleaseSigning"
}
if (providers.environmentVariable("CI").isPresent && releaseBuildRequested) {
    if (!releaseSigningConfigured) {
        throw GradleException("Release signing inputs are required for CI release builds.")
    }
    if (!releaseVerificationRequested) {
        throw GradleException("CI release builds must request verifyReleaseSigning.")
    }
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

val releaseKeystoreFile = if (releaseSigningConfigured) {
    val encoded = releaseSigningVariables.getValue("WEARTAK_RELEASE_KEYSTORE_B64").get()
    val keystoreBytes = try {
        Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() })
    } catch (_: IllegalArgumentException) {
        throw GradleException("WEARTAK_RELEASE_KEYSTORE_B64 is not valid base64.")
    }
    if (keystoreBytes.isEmpty()) {
        keystoreBytes.fill(0)
        throw GradleException("WEARTAK_RELEASE_KEYSTORE_B64 is empty.")
    }
    var temporaryKeystore: java.nio.file.Path? = null
    var ready = false
    try {
        val path = Files.createTempFile("weartak-plugin-release-", ".jks")
        temporaryKeystore = path
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
            throw GradleException("Release signing requires a private POSIX temporary directory.")
        }
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
        Files.write(
            path,
            keystoreBytes,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        ready = true
    } finally {
        keystoreBytes.fill(0)
        if (!ready) temporaryKeystore?.let { Files.deleteIfExists(it) }
    }
    val signingFile = requireNotNull(temporaryKeystore)
    gradle.addBuildListener(object : BuildAdapter() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun buildFinished(result: BuildResult) {
            Files.deleteIfExists(signingFile)
        }
    })
    signingFile.toFile()
} else {
    null
}

val wearTakReleaseSignerSha256 = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"

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
    signingConfigs {
        if (releaseSigningConfigured) {
            create("wearTakRelease") {
                storeFile = releaseKeystoreFile
                storePassword = releaseSigningVariables.getValue("RELEASE_STORE_PASSWORD").get()
                keyAlias = releaseSigningVariables.getValue("RELEASE_KEY_ALIAS").get()
                keyPassword = releaseSigningVariables.getValue("RELEASE_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("wearTakRelease")
            } else {
                null
            }
        }
    }
    sourceSets.getByName("test").java.srcDir("src/sdkAdapter/java")
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

val cleanupReleaseSigningInput = tasks.register("cleanupReleaseSigningInput") {
    doLast {
        releaseKeystoreFile?.let { Files.deleteIfExists(it.toPath()) }
    }
}

tasks.configureEach {
    if (name == "packageRelease") {
        finalizedBy(cleanupReleaseSigningInput)
    }
}

tasks.register("verifyReleaseSigning") {
    dependsOn("assembleRelease")

    doLast {
        val releaseApkDirectory = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val expectedApkName = if (releaseSigningConfigured) "app-release.apk" else "app-release-unsigned.apk"
        val releaseApks = releaseApkDirectory.listFiles()
            .orEmpty()
            .filter { it.extension.equals("apk", ignoreCase = true) }
        val releaseApk = releaseApks.singleOrNull { it.name == expectedApkName }
            ?: throw GradleException("Expected exactly one $expectedApkName release artifact.")

        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val apksignerName = if (windows) "apksigner.bat" else "apksigner"
        val apksigner = androidComponents.sdkComponents.sdkDirectory.get().asFile
            .resolve("build-tools")
            .listFiles()
            .orEmpty()
            .sortedByDescending { it.name }
            .map { it.resolve(apksignerName) }
            .firstOrNull { it.isFile }
            ?: throw GradleException("Android build-tools apksigner is unavailable.")

        val command = mutableListOf<String>()
        if (windows) command.addAll(listOf("cmd.exe", "/c", apksigner.absolutePath))
        else command.add(apksigner.absolutePath)
        command.addAll(listOf("verify", "--print-certs", releaseApk.absolutePath))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = exec {
            commandLine(command)
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        val diagnostics = stdout.toString("UTF-8") + stderr.toString("UTF-8")
        if (releaseSigningConfigured) {
            if (result.exitValue != 0) {
                throw GradleException("Release APK signature verification failed.")
            }
            val signers = Regex("Signer #\\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})")
                .findAll(diagnostics)
                .map { it.groupValues[1].lowercase() }
                .toList()
            if (signers != listOf(wearTakReleaseSignerSha256)) {
                throw GradleException("Release APK signer does not match the pinned WearTAK signer.")
            }
            logger.lifecycle("Release APK signer matches pinned WearTAK certificate.")
        } else {
            if (result.exitValue == 0 || !diagnostics.contains("DOES NOT VERIFY", ignoreCase = true)) {
                throw GradleException("Release APK was not confirmed unsigned.")
            }
            logger.lifecycle("Release APK is explicitly unsigned; no signing inputs were provided.")
        }
    }
}
