plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.newolf.monitor"
    compileSdk = 37

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.androidx.core.ktx)
    compileOnly(libs.material)
//    compileOnly(libs.junit)
//    androidTestImplementation(libs.androidx.espresso.core)
//    androidTestImplementation(libs.androidx.junit)
}