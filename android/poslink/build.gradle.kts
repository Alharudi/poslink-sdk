import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

// ---- Coordinates: ly.dojoon.poslink:poslink-android:<SDK_VERSION> ----

// The one place the version lives is PosLinkInfo.SDK_VERSION (the SDK reports it at runtime too).
val sdkVersion: String = run {
    val source = layout.projectDirectory.file("src/main/kotlin/ly/dojoon/poslink/PosLinkInfo.kt")
    val text = providers.fileContents(source).asText.orNull
        ?: throw GradleException("Can't read ${source.asFile}: it must define SDK_VERSION, the SDK's version.")
    Regex("""SDK_VERSION\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        ?: throw GradleException("""No SDK_VERSION = "x.y.z" in ${source.asFile}. The published version is read from there.""")
}

group = "ly.dojoon.poslink"
version = sdkVersion

val artifactName = "poslink-android"

// Project page for the POM (android/gradle.properties).
val repoUrl: String = providers.gradleProperty("poslink.repoUrl").orNull?.trim()?.removeSuffix("/")
    ?: throw GradleException("Set poslink.repoUrl in android/gradle.properties (the project's web page, used in the POM).")
val repoPath = repoUrl.substringAfter("://").removeSuffix(".git") // github.com/owner/repo

// Signing key (ASCII-armored secret key) from Gradle properties, e.g. env ORG_GRADLE_PROJECT_signingInMemoryKey.
val signingKey: String? = providers.gradleProperty("signingInMemoryKey").orNull?.takeIf { it.isNotBlank() }
val signingKeyPassword: String? = providers.gradleProperty("signingInMemoryKeyPassword").orNull

android {
    namespace = "ly.dojoon.poslink"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // Apps may compile against SDK 34 or newer; the AGP default would demand 36.
        aarMetadata {
            minCompileSdk = 34
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Keep the AAR usable from apps on Kotlin 2.0+: write 2.0 metadata, use only 2.0 APIs, and depend on
// stdlib 2.0.21 instead of the compiler's own (newer) stdlib.
kotlin {
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    // Public API: suspend functions and StateFlow/SharedFlow, so consumers need it to compile.
    // 1.9.0 is built with Kotlin 2.0; apps can still move to a newer coroutines themselves.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Android's org.json is a stub in local unit tests; use the real one there.
    testImplementation("org.json:json:20180813")
}

// ---- Publishing ----

val stagingRepo = layout.buildDirectory.dir("staging-repo")

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = artifactName
            version = sdkVersion

            pom {
                name.set("POS Link Android SDK")
                description.set(
                    "Connects a cashier app to a Sunmi P3 payment terminal over USB or Bluetooth " +
                        "with a paired, signed link, and runs sale, cancel and status.",
                )
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("dojoon")
                        name.set("Dojoon")
                        organization.set("Dojoon")
                        organizationUrl.set("https://dojoon.ly")
                    }
                }
                scm {
                    url.set(repoUrl)
                    connection.set("scm:git:https://$repoPath.git")
                    developerConnection.set("scm:git:ssh://git@$repoPath.git")
                }
            }
        }
    }
    repositories {
        // Local Maven repo; centralBundle zips it for the Sonatype Central Portal.
        maven {
            name = "staging"
            url = uri(stagingRepo)
        }
        // GitHub Pages: -Pposlink.pagesDir=<checkout of the gh-pages branch> publishes into its maven/
        // folder (task publishReleasePublicationToPagesRepository). See docs/releasing.md.
        providers.gradleProperty("poslink.pagesDir").orNull?.let { dir ->
            maven {
                name = "pages"
                url = uri(rootDir.parentFile.toPath().resolve(dir).resolve("maven"))
            }
        }
    }
}

// AGP creates the "release" component while the variants are created, after this script runs.
afterEvaluate {
    publishing.publications.named<MavenPublication>("release") {
        from(components["release"])
    }
}

signing {
    // Signs only when a key is given; without one, local publishing works unsigned.
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingKeyPassword)
        sign(publishing.publications)
    }
}

// ---- Central Portal bundle ----

val hasSigningKey = signingKey != null

val checkCentralSigning by tasks.registering {
    group = "publishing"
    description = "Fails unless a signing key is configured (Maven Central rejects unsigned uploads)."
    doLast {
        if (!hasSigningKey) {
            throw GradleException(
                "centralBundle needs a signing key: Maven Central rejects unsigned artifacts. Set the Gradle " +
                    "properties signingInMemoryKey (ASCII-armored secret key) and signingInMemoryKeyPassword, " +
                    "for example with env ORG_GRADLE_PROJECT_signingInMemoryKey / ORG_GRADLE_PROJECT_signingInMemoryKeyPassword.",
            )
        }
    }
}

val cleanStagingRepo by tasks.registering(Delete::class) {
    group = "publishing"
    description = "Deletes build/staging-repo."
    mustRunAfter(checkCentralSigning)
    delete(stagingRepo)
}

val publishToStaging = tasks.named("publishReleasePublicationToStagingRepository") {
    mustRunAfter(cleanStagingRepo)
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Signed release in build/staging-repo, zipped for manual upload to the Sonatype Central Portal."
    dependsOn(checkCentralSigning, cleanStagingRepo, publishToStaging)
    from(stagingRepo) {
        exclude("**/maven-metadata*")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("$artifactName-$sdkVersion-central-bundle.zip")
}
