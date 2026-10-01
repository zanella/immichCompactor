plugins {
    val kotlinVersion = "2.3.21"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.allopen") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("io.quarkus")
    id("io.mvnpm.gradle.plugin.native-java-plugin") version "1.0.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "raf.zane"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    // Quarkus core
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-config-yaml")

    // REST
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-kotlin-serialization")
    implementation("io.quarkus:quarkus-rest-client")
    implementation("io.quarkus:quarkus-rest-client-kotlin-serialization")

    // Database
    val jdsl3Version = "3.5.5"

    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("com.linecorp.kotlin-jdsl:jpql-dsl:$jdsl3Version")
    implementation("com.linecorp.kotlin-jdsl:jpql-render:$jdsl3Version")
    implementation("com.linecorp.kotlin-jdsl:support:$jdsl3Version")
    implementation("io.quarkiverse.jdbc:quarkus-jdbc-sqlite:3.0.11")
    implementation("io.quarkus:quarkus-flyway")

    // Validation
    // implementation("org.hibernate.validator:hibernate-validator:8.0.1.Final")
    // implementation("io.quarkus:quarkus-hibernate-validator")

    // FrontEnd
    implementation("io.quarkiverse.quinoa:quarkus-quinoa:2.9.0")

    // Misc
    implementation("io.quarkus:quarkus-smallrye-openapi")

    // Test
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
    annotation("jakarta.transaction.Transactional")
}

tasks.test {
    useJUnitPlatform()
}
