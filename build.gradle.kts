import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.bmuschko.gradle.docker.tasks.container.*
import com.bmuschko.gradle.docker.tasks.image.*

plugins {
  java
  application
  idea
  id("com.github.johnrengelman.shadow") version "5.0.0"
  id("com.bmuschko.docker-remote-api") version "4.6.2"
  id("net.nemerosa.versioning") version "2.8.2"
  id("com.diffplug.gradle.spotless") version "3.20.0"
}

repositories {
  mavenCentral()
  jcenter()
}

spotless {
  java {
    licenseHeaderFile("spotless.license.java")
    googleJavaFormat()
    encoding("UTF-8")
  }
}

val vertxVersion = "3.6.3"
val junitVersion = "5.3.2"
val sfl4jVersion = "1.7.26"
val logbackVersion = "1.2.3"
val lombokVersion = "1.18.6"

val mainVerticleName = "io.weba.notifier.verticle.DeploymentVerticle"
val watchForChange = "src/**/*.java"
val doOnChange = "${projectDir}/gradlew classes"

val dockerBaseImage: String by project
val dockerImageName: String by project
val dockerMaintainer: String by project

val jvmDefaultOpts: String by project
val jvmDebuggerPort: String by project

dependencies {
  implementation("io.vertx:vertx-core:$vertxVersion")
  implementation("io.vertx:vertx-rx-java2:$vertxVersion")
  implementation("io.vertx:vertx-config:$vertxVersion")
  implementation("io.vertx:vertx-hazelcast:$vertxVersion")

  implementation("ch.qos.logback:logback-core:$logbackVersion")
  implementation("ch.qos.logback:logback-classic:$logbackVersion")
  implementation("org.slf4j:slf4j-api:$sfl4jVersion")

  compileOnly("org.projectlombok:lombok:$lombokVersion")
  testCompileOnly("org.projectlombok:lombok:$lombokVersion")
  annotationProcessor("org.projectlombok:lombok:$lombokVersion")
  testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

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
  applicationDefaultJvmArgs = jvmDefaultOpts
    .replace("\\", "")
    .split(" ")
    .plus("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=${jvmDebuggerPort}")
}

docker {
  registryCredentials {
    url.set(getConfigurationProperty("DOCKER_REGISTRY_URL", "dockerRegistryUrl"))
    username.set(getConfigurationProperty("DOCKER_REGISTRY_USERNAME", "dockerRegistryUsername"))
    password.set(getConfigurationProperty("DOCKER_REGISTRY_PASSWORD", "dockerRegistryPassword"))
  }
}

tasks {
  test {
    useJUnitPlatform()
  }

  getByName<JavaExec>("run") {
    args = listOf(
      "run",
      mainVerticleName,
      "--redeploy=${watchForChange}",
      "--launcher-class=${application.mainClassName}",
      "--on-redeploy=${doOnChange}"
    )
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

tasks.register<Copy>("dockerSyncJar") {
  dependsOn(shadowJar)
  from(shadowJar.archivePath)
  into("$buildDir/docker")
  setGroup("docker")
  description = "Copy fat jar to docker directory."
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
  environmentVariable("DEFAULT_JAVA_OPTS", jvmDefaultOpts)
  environmentVariable("JAVA_OPTS", "\"\"")
  entryPoint("sh", "-c")
  defaultCommand("java \$DEFAULT_JAVA_OPTS \$JAVA_OPTS -jar /app/app.jar")
  exposePort(8080)
  label(mapOf("maintainer" to dockerMaintainer))
  dependsOn(dockerSyncJar)
  setGroup("docker")
  description = "Create Dockerfile."
}

val dockerBuildImage by tasks.creating(DockerBuildImage::class) {
  tags.add("$dockerImageName:${versioning.info.full}")
  noCache.set(true)
  dependsOn(dockerCreateDockerfile)
  setGroup("docker")
  description = "Build docker image."
}

tasks.create("dockerPushImage", DockerPushImage::class) {
  dependsOn(dockerBuildImage)
  tag.set(versioning.info.full)
  imageName.set(dockerImageName)
  setGroup("docker")
  description = "Push image to registry (tag is resolved base on versioning plugin)."
}

fun getConfigurationProperty(envVar: String, sysProp: String): String {
  return System.getenv(envVar) ?: project.findProperty(sysProp).toString()
}

