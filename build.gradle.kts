import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.bmuschko.gradle.docker.tasks.container.*
import com.bmuschko.gradle.docker.tasks.image.*

plugins {
  java
  application
  id("com.github.johnrengelman.shadow") version "5.0.0"
  id("com.bmuschko.docker-remote-api") version "4.6.2"
  id("net.nemerosa.versioning") version "2.8.2"
}

repositories {
  mavenCentral()
}

val vertxVersion = "3.6.3"
val junitVersion = "5.3.2"

dependencies {
  implementation("io.vertx:vertx-core:$vertxVersion")

  testImplementation("io.vertx:vertx-junit5:$vertxVersion")
  testImplementation("io.vertx:vertx-web-client:$vertxVersion")
  testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
}

application {
  mainClassName = "io.vertx.core.Launcher"
}

val mainVerticleName = "io.vertx.starter.MainVerticle"
val watchForChange = "src/**/*.java"
val doOnChange = "${projectDir}/gradlew classes"

tasks {
  test {
    useJUnitPlatform()
  }

  getByName<JavaExec>("run") {
    args = listOf("run", mainVerticleName, "--redeploy=${watchForChange}", "--launcher-class=${application.mainClassName}", "--on-redeploy=${doOnChange}")
  }

  withType<ShadowJar> {
    classifier = "fat"
    manifest {
      attributes["Main-Verticle"] = mainVerticleName
    }
    mergeServiceFiles {
      include("META-INF/services/io.vertx.core.spi.VerticleFactory")
    }
  }
}

docker {
  registryCredentials {
    url.set(getConfigurationProperty("DOCKER_REGISTRY_URL", "dockerRegistryUrl"))
    username.set(getConfigurationProperty("DOCKER_REGISTRY_USERNAME", "dockerRegistryUsername"))
    password.set(getConfigurationProperty("DOCKER_REGISTRY_PASSWORD", "dockerRegistryPassword"))
  }
}

val dockerBaseImage: String by project
val dockerImageName: String by project
val dockerMaintainer: String by project
val dockerDefaultJavaOpts: String by project

tasks.register<Copy>("dockerSyncJar") {
  dependsOn(shadowJar)
  from(shadowJar.archivePath)
  into("$buildDir/docker")
}

val shadowJar = tasks["shadowJar"] as ShadowJar
tasks.getByName("shadowJar", ShadowJar::class).apply {
  isZip64 = true
}
val dockerSyncJar = tasks["shadowJar"]

val dockerCreateDockerfile by tasks.creating(Dockerfile::class) {
  destFile.set(file("build/docker/Dockerfile"))
  from(dockerBaseImage)
  copyFile("${rootProject.name}-fat.jar", "/app/app.jar")
  environmentVariable("DEFAULT_JAVA_OPTS", dockerDefaultJavaOpts)
  environmentVariable("JAVA_OPTS", "\"\"")
  entryPoint("sh", "-c")
  defaultCommand("java \$DEFAULT_JAVA_OPTS \$JAVA_OPTS -jar /app/app.jar")
  exposePort(8080)
  label(mapOf("maintainer" to dockerMaintainer))
  dependsOn(dockerSyncJar)
}

val dockerBuildImage by tasks.creating(DockerBuildImage::class) {
  tags.add("$dockerImageName:${versioning.info.full}")
  noCache.set(true)
  dependsOn(dockerCreateDockerfile)
}

tasks.create("dockerPushImage", DockerPushImage::class) {
  dependsOn(dockerBuildImage)
  tag.set(versioning.info.full)
  imageName.set(dockerImageName)
}

fun getConfigurationProperty(envVar: String, sysProp: String): String {
  return System.getenv(envVar) ?: project.findProperty(sysProp).toString()
}

