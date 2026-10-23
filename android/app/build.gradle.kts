plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.owreceipt.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.owreceipt.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.5"
        buildConfigField("boolean", "DEMO_MODE", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("../work/signing/demo-debug.keystore")
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO_MODE", "true")
        }
        create("live") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            applicationIdSuffix = ""
            versionNameSuffix = "-live"
            buildConfigField("boolean", "DEMO_MODE", "false")
            matchingFallbacks += "debug"
        }
        create("benchmark") {
            initWith(getByName("live"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            buildConfigField("boolean", "DEMO_MODE", "true")
            matchingFallbacks += "live"
        }
        release { isMinifyEnabled = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("release") {
        manifest.srcFile("src/live/AndroidManifest.xml")
        java.srcDir("src/live/kotlin")
    }
    testBuildType = providers.gradleProperty("receiptTestBuildType").orElse("debug").get()
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation(project(":infra"))
    implementation(project(":ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.serialization.json)
    debugImplementation(libs.compose.test.manifest)
}
