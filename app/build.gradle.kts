import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.k3rn3l.aikeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.k3rn3l.aikeyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.autofill:autofill:1.1.0")
}

val sdkDir: File = run {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!fromEnv.isNullOrBlank()) File(fromEnv) else File(System.getProperty("user.home"), "Android/Sdk")
}

val ndkDir: File = run {
    val ndks = File(sdkDir, "ndk").listFiles()?.filter { it.isDirectory }.orEmpty()
    ndks.maxByOrNull { it.name } ?: File(sdkDir, "ndk")
}

val cargoBin: String = System.getenv("CARGO")
    ?: File(System.getProperty("user.home"), ".cargo/bin/cargo").absolutePath

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Cross-compiles the Rust transcription core for Android arm64."

    workingDir = rootProject.file("rust")
    environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
    commandLine(
        cargoBin, "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release",
    )

    inputs.dir(rootProject.file("rust/src"))
    inputs.file(rootProject.file("rust/Cargo.toml"))
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") {
    dependsOn(cargoBuild)
}

tasks.register<Exec>("cargoClean") {
    group = "build"
    workingDir = rootProject.file("rust")
    commandLine(cargoBin, "clean")
}
