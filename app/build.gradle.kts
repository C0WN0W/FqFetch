plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cownow.fqfetch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cownow.fqfetch"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Xposed / LSPosed API：仅编译期，运行期由框架提供
    compileOnly(libs.xposed.api)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
