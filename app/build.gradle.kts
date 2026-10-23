plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.example.screentranslator"; compileSdk = 35
    defaultConfig { applicationId = "com.example.screentranslator"; minSdk = 26; targetSdk = 29; versionCode = 10; versionName = "0.8" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
