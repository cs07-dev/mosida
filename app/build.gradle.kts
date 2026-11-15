plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hmgchallenge.mosida"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hmgchallenge.mosida"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        val local = rootProject.file("local.properties").takeIf { it.exists() }?.readLines().orEmpty()
        fun prop(key: String) = local.firstOrNull { it.startsWith("$key=") }?.substringAfter("=").orEmpty()
        buildConfigField("String", "OMB_TOKEN", "\"${prop("omb.token")}\"")
        buildConfigField("String", "SEOUL_KEY", "\"${prop("seoul.key")}\"")
        buildConfigField("String", "TMAP_KEY", "\"${prop("tmap.key")}\"")
        buildConfigField("String", "LLM_URL", "\"${prop("llm.url")}\"")
        buildConfigField("String", "LLM_KEY", "\"${prop("llm.key")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${prop("llm.model")}\"")
        buildConfigField("String", "OMB_BOT", "\"d6d5d12c-c578-41ff-8e03-5367f1296165\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        // 공개 배포용 APK: 키를 비워서 빌드 (예시 데이터로 동작), 프로토타입이라 debug 서명 사용
        release {
            signingConfig = signingConfigs.getByName("debug")
            listOf("OMB_TOKEN", "SEOUL_KEY", "TMAP_KEY", "LLM_KEY").forEach { buildConfigField("String", it, "\"\"") }
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
