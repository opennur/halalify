import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.Sync

plugins {
    id("shellify.android.library")
    id("shellify.ksp")
}

// Must be top-level (not inside android {}) for Room's KSP processor to pick it up.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Termux reports GNU/Linux even though its Android linker can only load the
// Android variant bundled in sqlite-jdbc. Keep the extracted library in the
// Termux temp directory because Android restricts native library namespaces.
val termuxSqliteArchitecture = when (System.getProperty("os.arch")) {
    "aarch64" -> "aarch64"
    "arm" -> "arm"
    "x86_64" -> "x86_64"
    "x86" -> "x86"
    else -> null
}
val isTermuxJvm = System.getProperty("java.home").contains("/data/data/com.termux/")

if (isTermuxJvm && termuxSqliteArchitecture != null) {
    val termuxSqliteDirectory = file("${System.getProperty("java.io.tmpdir")}/shellify-room")
    val termuxSqliteResource =
        "org/sqlite/native/Linux-Android/$termuxSqliteArchitecture/libsqlitejdbc.so"
    val extractTermuxSqlite = tasks.register<Sync>("extractTermuxSqlite") {
        from({
            configurations.getByName("kspDebugKotlinProcessorClasspath").files
                .filter { it.name.startsWith("sqlite-jdbc-") }
                .map { zipTree(it) }
        }) {
            include(termuxSqliteResource)
            eachFile {
                relativePath = RelativePath(true, "libsqlitejdbc.so")
            }
            includeEmptyDirs = false
        }
        into(termuxSqliteDirectory)
    }

    tasks.configureEach {
        if (name == "kspDebugKotlin") {
            dependsOn(extractTermuxSqlite)
            doFirst {
                System.setProperty("org.sqlite.lib.path", termuxSqliteDirectory.absolutePath)
                System.setProperty("org.sqlite.lib.name", "libsqlitejdbc.so")
            }
        }
    }
}

android {
    namespace = "io.shellify.core.database"

    sourceSets {
        // Expose the generated schema JSON files as assets in the androidTest APK so
        // MigrationTestHelper can read them when validating migration correctness.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SQLCipher
    implementation(libs.sqlcipher) { isTransitive = true }
    implementation(libs.androidx.sqlite.ktx)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
