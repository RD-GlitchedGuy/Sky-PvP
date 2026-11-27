plugins {
    java
}

group = "de.skypvp"
version = "v26.0"

repositories {
    mavenCentral()
    maven("https://repo.alessiodp.com/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.citizensnpcs.co/repo")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("net.citizensnpcs:citizens-main:2.0.44-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

