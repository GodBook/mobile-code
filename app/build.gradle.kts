plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runtimeAbi = providers.gradleProperty("mcAbi").orElse("arm64-v8a").get()
val toolchainFolder = layout.projectDirectory.dir("toolchains/$runtimeAbi")

android {
    namespace = "dev.mobilecode.ide"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.mobilecode.ide"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("mcVersionCode").orElse("8").get().toInt()
        versionName = providers.gradleProperty("mcVersionName").orElse("0.3.1").get()
        ndk { abiFilters += runtimeAbi }
        buildConfigField("String", "RUNTIME_ABI", "\"$runtimeAbi\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"GodBook/mobile-code\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
                arguments += "-DMC_GRAMMAR_ROOT=${rootProject.projectDir.invariantSeparatorsPath}/.toolchains/grammars"
                arguments += "-DMC_PROGRAM_OUTPUT=${layout.buildDirectory.get().asFile.invariantSeparatorsPath}/native-programs"
                arguments += "-DANDROID_STL=c++_static"
                targets += listOf("mc_process", "mc_syntax", "mc_runner", "dt_mobile")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets.getByName("main") {
        assets.srcDir(toolchainFolder.dir("assets"))
        assets.srcDir(layout.buildDirectory.dir("generated/runtime-assets").get().asFile)
        jniLibs.srcDir(toolchainFolder.dir("jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("native-programs").get().asFile)
    }
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!*.pyc:!__pycache__:!*.saving" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = providers.gradleProperty("mcShrinkUi").orElse("true").get().toBoolean()
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("p0") { initWith(getByName("release")); matchingFallbacks += "release"; isDebuggable=false }
    }
    testBuildType = providers.gradleProperty("mcTestBuildType").orElse("p0").get()
    testOptions { unitTests.isReturnDefaultValues = true }
}

val runtimeJar = tasks.register<Copy>("packageJavaRuntime") {
    dependsOn(":runtime-java:jar")
    from(project(":runtime-java").layout.buildDirectory.file("libs/mobilecode-runtime.jar"))
    into(layout.buildDirectory.dir("generated/runtime-assets/runtime"))
}
tasks.named("preBuild") { dependsOn(runtimeJar) }
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders") && !name.contains("AndroidTest")) {
        val variant = name.removePrefix("merge").removeSuffix("JniLibFolders")
        dependsOn("externalNativeBuild$variant")
    }
    if (name == "assembleRelease") {
        doFirst {
            check(toolchainFolder.file("assets/toolchains/manifest.json").asFile.exists()) {
                "Offline toolchains missing. Run tools/prepare_toolchains.py --abi $runtimeAbi first."
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")
    implementation("io.github.rosemoe:editor:0.24.6")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
