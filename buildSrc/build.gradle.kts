plugins {
    kotlin("jvm") version "2.2.0"
}

repositories {
    mavenCentral()
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Kotlin 2.2.0에 맞는 KSP 버전
    // Maven Central에서 확인한 버전 사용
    val kspVersion = "2.3.4"
    implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion")
    implementation("com.squareup:kotlinpoet:1.16.0")
    implementation("com.squareup:kotlinpoet-ksp:1.16.0")
}
