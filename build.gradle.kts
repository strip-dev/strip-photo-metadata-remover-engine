plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.stripdev.strip.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    
    implementation("androidx.exifinterface:exifinterface:1.3.6")

    // Metadata deep reading
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha5")

    // Needed for Uri, ContentResolver, Bitmap, etc.
    implementation("androidx.annotation:annotation:1.6.0")
}
