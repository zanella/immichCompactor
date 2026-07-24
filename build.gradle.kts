plugins {
    val kotlinVersion = "2.3.21"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.allopen") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("io.quarkus")
    id("com.google.osdetector") version "1.7.3"
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

    // Rest
    implementation("io.quarkus:quarkus-rest-client")
    implementation("io.quarkus:quarkus-rest-client-kotlin-serialization")

    // Database
    val jdsl3Version = "3.5.5"

    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("com.linecorp.kotlin-jdsl:jpql-dsl:$jdsl3Version")
    implementation("com.linecorp.kotlin-jdsl:jpql-render:$jdsl3Version")
    implementation("com.linecorp.kotlin-jdsl:support:$jdsl3Version")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:10.20.0")

    // Validation
    implementation("org.hibernate.validator:hibernate-validator:8.0.1.Final")
    implementation("io.quarkus:quarkus-hibernate-validator")

    // Utils
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

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
