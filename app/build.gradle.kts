import com.android.build.api.variant.ApplicationVariant
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.CRC32
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.Internal
import javax.inject.Inject

abstract class GitCommitCountValueSource : ValueSource<Int, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Int {
        val output = ByteArrayOutputStream()
        return try {
            execOperations.exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                standardOutput = output
                isIgnoreExitValue = true
            }
            output.toString().trim().toIntOrNull() ?: 1
        } catch (e: Exception) {
            1
        }
    }
}

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.orion.frostkeys"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.of(GitCommitCountValueSource::class.java) {}.get()
        versionName = "2.5.9"
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"${applicationId}.stickercontentprovider\"")
        manifestPlaceholders["stickerAuthority"] = "${applicationId}.stickercontentprovider"
        manifestPlaceholders["stickerProviderAuthority"] = "${applicationId}.stickercontentprovider"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        externalNativeBuild {
            ndkBuild {
                arguments.add("-j1")
            }
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = false
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
            manifestPlaceholders["stickerProviderAuthority"] = "${defaultConfig.applicationId}.debug.stickercontentprovider"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            manifestPlaceholders["stickerProviderAuthority"] = "${defaultConfig.applicationId}.debug.stickercontentprovider"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "FrostKeys_${defaultConfig.versionName}-${variant.buildType}.apk"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts.add("**/libsharpyuv.so")
            pickFirsts.add("**/libwebp.so")
            pickFirsts.add("**/libwebpcodec_jni.so")
            pickFirsts.add("**/libwebpdemux.so")
            pickFirsts.add("**/libwebpmux.so")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        target {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // newer than 2025.11.01 contains androidx.compose.material:material-android:1.10.0, which requires minSdk 23
    // maybe it's possible to use tools:overrideLibrary="androidx.compose.material" as it's not used explicitly, but probably this is just going to crash
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.aureusapps.android:webp-android:1.1.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.chrisbanes.haze:haze:0.7.3")

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}

@UntrackedTask(because = "Patches compiled binaries in place for 16 KB page size support")
abstract class PatchElf16KbTask : DefaultTask() {
    @get:Internal
    abstract val buildDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val buildDir = buildDirectory.get().asFile
        val targetAbis = setOf("arm64-v8a", "x86_64")
        listOf("intermediates/merged_native_libs", "intermediates/stripped_native_libs").forEach { sub ->
            val dir = File(buildDir, sub)
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { file ->
                    val parent = file.parentFile?.name ?: ""
                    if (parent in targetAbis) {
                        patchElf(file)
                    }
                }
            }
        }
        val bundleDir = File(buildDir, "outputs/bundle")
        if (bundleDir.exists()) {
            bundleDir.walkTopDown().filter { it.isFile && it.name.endsWith(".aab") }.forEach { file ->
                patchAab(file)
            }
        }
    }

    private fun patchElf(file: File) {
        val bytes = file.readBytes()
        if (patchElfBytes(bytes)) {
            file.writeBytes(bytes)
            logger.lifecycle("[16KB Patch] Aligned ${file.name} in ${file.parentFile?.name} to 16 KB")
        }
    }

    private fun patchAab(file: File) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        var anyPatched = false
        ZipFile(file).use { zipIn ->
            tempFile.outputStream().use { fos ->
                ZipOutputStream(fos).use { zipOut ->
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val bytes = zipIn.getInputStream(entry).readBytes()
                        if (entry.name.endsWith(".so") && ("arm64-v8a" in entry.name || "x86_64" in entry.name)) {
                            if (patchElfBytes(bytes)) {
                                anyPatched = true
                                logger.lifecycle("[16KB Patch] Aligned ${entry.name} inside AAB to 16 KB")
                            }
                        }
                        val newEntry = ZipEntry(entry.name)
                        newEntry.time = entry.time
                        newEntry.method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.size = bytes.size.toLong()
                            newEntry.compressedSize = bytes.size.toLong()
                            val crc = CRC32()
                            crc.update(bytes)
                            newEntry.crc = crc.value
                        }
                        zipOut.putNextEntry(newEntry)
                        zipOut.write(bytes)
                        zipOut.closeEntry()
                    }
                }
            }
        }
        if (anyPatched) {
            if (file.delete()) {
                tempFile.renameTo(file)
            }
        } else {
            tempFile.delete()
        }
    }

    private fun patchElfBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 64 || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) return false
        val eiClass = bytes[4].toInt()
        if (eiClass != 2) return false
        val isLittleEndian = bytes[5].toInt() == 1
        val buffer = ByteBuffer.wrap(bytes).order(if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val phOff = buffer.getLong(32).toInt()
        val phEntrySize = buffer.getShort(54).toInt()
        val phNum = buffer.getShort(56).toInt()

        var patched = false
        for (i in 0 until phNum) {
            val entryOffset = phOff + (i * phEntrySize)
            val pType = buffer.getInt(entryOffset)
            if (pType == 1) { // PT_LOAD
                val pAlign = buffer.getLong(entryOffset + 48)
                if (pAlign < 0x4000L) {
                    buffer.putLong(entryOffset + 48, 0x4000L)
                    patched = true
                }
            }
        }
        return patched
    }
}

val patchNativeLibs16Kb = tasks.register<PatchElf16KbTask>("patchNativeLibs16Kb") {
    buildDirectory.set(layout.buildDirectory)
}

tasks.matching {
    (it.name.startsWith("strip") && it.name.endsWith("DebugSymbols")) ||
    (it.name.startsWith("merge") && it.name.endsWith("NativeLibs"))
}.configureEach {
    finalizedBy(patchNativeLibs16Kb)
}

patchNativeLibs16Kb.configure {
    mustRunAfter(tasks.matching {
        (it.name.startsWith("strip") && it.name.endsWith("DebugSymbols")) ||
        (it.name.startsWith("merge") && it.name.endsWith("NativeLibs"))
    })
}

tasks.matching {
    (it.name.startsWith("package") && it.name.endsWith("Bundle")) ||
    (it.name.startsWith("bundle") && it.name != "bundle" && !it.name.contains("Resources") && !it.name.contains("Classes"))
}.configureEach {
    dependsOn(patchNativeLibs16Kb)
}
