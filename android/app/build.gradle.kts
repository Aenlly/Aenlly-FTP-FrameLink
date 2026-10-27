plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
android {
    namespace = "app.framelink.camera"
    compileSdk = 36
    defaultConfig { applicationId = "app.framelink.camera"; minSdk = 26; targetSdk = 36; versionCode = 4; versionName = "0.1.3"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1") }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(files("libs/camera.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    implementation("commons-net:commons-net:3.13.0")
    implementation("dev.chrisbanes.haze:haze:1.5.3")
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.00"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
