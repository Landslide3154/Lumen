import mihon.buildlogic.Config
import mihon.buildlogic.getBuildTime
import mihon.buildlogic.getCommitCount
import mihon.buildlogic.getGitSha

plugins {
    id("mihon.android.application")
    id("mihon.android.application.compose")
    id("com.github.zellius.shortcut-helper")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    alias(libs.plugins.aboutLibraries)
    id("com.github.ben-manes.versions")
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

shortcutHelper.setFilePath("./shortcuts.xml")

// KMK -->
/** Lumen 自有版本序号：每次交付 +1，版本号显示为 上游版本.01 / .02 … */
val lumenRevision = 17
val lumenVersionName = "1.14.1." + lumenRevision.toString().padStart(2, '0')
// KMK <--

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        // KMK -->
        applicationId = "app.lumen"
        // KMK <--

        // KMK -->
        versionCode = 82 * 100 + lumenRevision
        versionName = lumenVersionName
        // KMK <--

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        val debug by getting {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-${getCommitCount()}"
            isPseudoLocalesEnabled = true
        }
        val release by getting {
            isMinifyEnabled = Config.enableCodeShrink
            isShrinkResources = Config.enableCodeShrink

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = true)}\"")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("releaseTest") {
            initWith(release)

            applicationIdSuffix = ".rt"
            isMinifyEnabled = false
            isShrinkResources = false

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".beta"

            // KMK --> 交付版不追加提交号，版本号就是 1.14.1.01 这样
            versionNameSuffix = ""
            // KMK <--
            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
        }
        create("benchmark") {
            initWith(release)

            isDebuggable = false
            isProfileable = true
            versionNameSuffix = "${debug.versionNameSuffix}-benchmark"
            applicationIdSuffix = ".benchmark"

            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
    }

    sourceSets {
        getByName("preview").res.srcDirs("src/beta/res")
        getByName("benchmark").res.srcDirs("src/debug/res")
    }

    splits {
        abi {
            // KMK --> 只出 arm64-v8a 一个包
            isEnable = true
            isUniversalApk = false
            reset()
            include("arm64-v8a")
            // KMK <--
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true

        // Disable some unused things
        renderScript = false
        shaders = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    implementation(projects.i18n)
    // KMK -->
    implementation(projects.i18nKmk)
    // KMK <--
    // SY -->
    implementation(projects.i18nSy)
    // SY <--
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.animation.graphics)
    debugImplementation(compose.ui.tooling)
    implementation(compose.ui.tooling.preview)
    implementation(compose.ui.util)

    implementation(androidx.interpolator)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)

    implementation(androidx.sqlite.bundled)

    // SY -->
    implementation(sylibs.sqlcipher)
    // SY <--

    implementation(kotlinx.reflect)
    implementation(kotlinx.immutables)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)

    // AndroidX libraries
    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.biometricktx)
    implementation(androidx.constraintlayout)
    implementation(androidx.corektx)
    implementation(androidx.splashscreen)
    implementation(androidx.recyclerview)
    implementation(androidx.viewpager)
    implementation(androidx.profileinstaller)

    implementation(androidx.bundles.lifecycle)

    // Job scheduling
    implementation(androidx.workmanager)

    // RxJava
    implementation(libs.rxjava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt.android) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(kotlinx.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.disklrucache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.preferencektx)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoview)
    implementation(libs.directionalviewpager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.richeditor.compose)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.compose.materialmotion)
    implementation(libs.swipe)
    implementation(libs.compose.webview)
    implementation(libs.compose.grid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // KMK -->
    implementation(libs.palette.ktx)
    implementation(libs.haze)
    implementation(compose.colorpicker)
    implementation(projects.flagkit)
    // KMK <--

    // Logging
    implementation(libs.timber)
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakcanary.android)
    implementation(libs.leakcanary.plumber)

    testImplementation(kotlinx.coroutines.test)

    // SY -->
    // Better logging (EH)
    implementation(sylibs.xlog)

    // RatingBar (SY)
    implementation(sylibs.ratingbar)
    implementation(sylibs.composeRatingbar)

    // Google drive
    implementation(sylibs.google.api.services.drive)

    // ZXing Android Embedded
    implementation(sylibs.zxing.android.embedded)
}

androidComponents {
    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}

buildscript {
    dependencies {
        classpath(kotlinx.gradle)
    }
}

// KMK -->
// APK 文件名带上软件名、ABI 与版本号，例如 Lumen-arm64-v8a-1.14.1.16.apk。
// 只复制、不移动 AGP 自己的产物：AGP 的增量打包以上一次的 APK 为基准，把它移走再构建
// 只会写出变化的 dex，得到一个装不上的残包（实测 68MB → 1.5MB）。
// AGP 8 的 VariantOutput 没有公开的 outputFileName 写入口，所以只能在构建后补一份。
tasks.matching { it.name.startsWith("assemble") }.configureEach {
    doLast {
        val variant = name.removePrefix("assemble").lowercase()
        val variantDir = file("build/outputs/apk/$variant")
        variantDir.listFiles { f: File -> f.isFile && f.extension == "apk" && !f.name.startsWith("Lumen-") }
            ?.forEach { apk ->
                // 原文件名形如 app-arm64-v8a-preview.apk，取出中间那段 ABI
                val abi = apk.nameWithoutExtension
                    .removePrefix("app-")
                    .removeSuffix("-$variant")
                    .takeIf { it.isNotEmpty() && it != "universal" }
                val suffix = abi?.let { "-$it" }.orEmpty()
                apk.copyTo(File(variantDir, "Lumen$suffix-$lumenVersionName.apk"), overwrite = true)
            }
    }
}
// KMK <--
