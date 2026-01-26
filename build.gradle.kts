plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("io.ktor.plugin") version "3.3.3"
    id("com.google.devtools.ksp") version "2.3.4"
}

group = "com.hana.orchestrator"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor  
    val ktorVersion = "2.3.10"
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-websockets-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-client-core:${ktorVersion}")
    implementation("io.ktor:ktor-client-cio-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:${ktorVersion}")
    // Ktor Content Negotiation for request/response
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    
    // Kotlinx serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.13")
    
    // Koog AI Framework - Latest stable version
    val koogVersion = "0.6.0"
    implementation("ai.koog:koog-agents:$koogVersion")
    implementation("ai.koog:koog-ktor:${koogVersion}")
    // LLM Providers - Ollama integration via Koog AI framework
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    
    // KSP 프로세서 (buildSrc에서 자동 포함)
    ksp(project(":buildSrc"))
}

application {
    mainClass.set("com.hana.orchestrator.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

ksp {
    arg("layer.package", "com.hana.orchestrator.layer")
}

tasks.test {
    useJUnitPlatform()
}