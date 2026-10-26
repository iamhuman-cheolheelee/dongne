import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM 단위 테스트에서 android.jar 의 org.json 스텁 대신 실제 구현을 쓴다
    testImplementation("org.json:json:20240303")
}

// release 서명: 저장소 밖 키를 keystore.properties(커밋 금지)로 가리킨다. 없으면 debug 키로 서명(개인 설치용)
//   storeFile=/절대/경로/release.jks
//   storePassword=…  keyAlias=…  keyPassword=…
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "dev.dongne.weather"
    compileSdk = 35
    defaultConfig { applicationId = "dev.dongne.weather"; minSdk = 31; targetSdk = 35; versionCode = 6; versionName = "1.4.1" }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
