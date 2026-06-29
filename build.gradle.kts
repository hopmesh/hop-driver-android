plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// HopDriver — the THIN Android platform driver: composes a libhop node + HopRuntime + the bearer
// modules this build wants, runs the pump loop. NO transport/beacon code — just wiring. The app
// depends on this. Mirror of drivers/apple/HopDriver.
android {
    namespace = "sh.hop.driver"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":hop-sdk"))
    api(project(":bearer-ble"))
    api(project(":bearer-lan"))
    api(project(":bearer-wifidirect"))
    api(project(":bearer-relay"))
}
