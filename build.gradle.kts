plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.piscessoft.sbl"
version = "1.0.0"

repositories {
    // XYZ internal Artifactory mirror of Maven Central.
    // Falls back to Maven Central for environments outside the corporate network.
    maven {
        name = "artifactory-maven-remote"
        url = uri("https://repository.saas.cagip.group.gca/artifactory/maven-remote")
        isAllowInsecureProtocol = false
    }
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.piscessoft.sbl.app.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
