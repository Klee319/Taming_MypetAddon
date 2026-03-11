plugins {
    java
}

group = "com.mypetaddon"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/") // MyPet
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(files("libs/MyPet-3.14.1-SNAPSHOT-b14.jar"))
    compileOnly("io.github.arcaneplugins:levelledmobs-plugin:4.0.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        // Shade HikariCP into the final jar
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    compileJava {
        options.encoding = "UTF-8"
    }
}
