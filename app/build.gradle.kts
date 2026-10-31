import com.android.build.api.variant.SigningConfigInfo
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Properties
import me.okonecny.gradlekeyring.PublicKeyringExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localSigningProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun signingProperty(name: String): String? =
    providers.gradleProperty(name).orNull ?: localSigningProperties.getProperty(name)

val keyringSigningEnabled = when (val value = signingProperty("keyringSigning") ?: "true") {
    "true" -> true
    "false" -> false
    else -> throw GradleException("keyringSigning must be true or false, received '$value'.")
}

fun requiredSigningProperty(name: String): String = signingProperty(name)
    ?.takeIf { it.isNotBlank() }
    ?: throw GradleException(
        "Set $name in local.properties or with -P$name. See docs/keyring-signing.html."
    )

fun signingPassword(name: String): String {
    val keyring = rootProject.extensions.getByType(PublicKeyringExtension::class.java)
    return keyring.secrets[name]?.takeUnless { it == "<not set>" || it.isEmpty() }
        ?: throw GradleException(
            "Signing secret '$name' is unavailable. Run ./scripts/init-keyring-signing.sh " +
                "or ./gradlew --no-configuration-cache -PkeyringSigning=false :setSecretValue --name=$name."
        )
}

android {
    namespace = "top.ntutn.awall"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.ntutn.awell"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Opt-in isolated install for devices with an existing differently signed APK.
            applicationIdSuffix = providers.gradleProperty("debugApplicationIdSuffix").orNull
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

// AGP resolves this provider only when a task needs Release signing information.
val releaseSigningConfig = providers.provider {
    if (!keyringSigningEnabled) {
        throw GradleException("Release signing is not configured. Set keyringSigning=true to enable it.")
    }
    // Signing task inputs contain passwords; never persist them in the configuration cache.
    @Suppress("DEPRECATION")
    if (gradle.startParameter.isConfigurationCacheRequested) {
        throw GradleException("Keyring signing requires --no-configuration-cache to avoid caching passwords.")
    }
    val keystore = rootProject.file(requiredSigningProperty("keyringStoreFile"))
    if (!keystore.isFile) {
        throw GradleException("Signing keystore does not exist: $keystore")
    }
    val alias = requiredSigningProperty("keyringKeyAlias")
    SigningConfigInfo(
        storeFile = keystore,
        storePassword = signingPassword("android_store_password"),
        keyAlias = alias,
        keyPassword = signingPassword("android_key_password"),
        storeType = KeyStore.getDefaultType(),
    )
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        if (keyringSigningEnabled) {
            variant.signingConfig?.from(releaseSigningConfig)
        }
    }
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Checks the release keystore, passwords, private key and certificate chain without building."
    notCompatibleWithConfigurationCache("Reads local signing credentials")
    doLast {
        val config = releaseSigningConfig.get()
        val keystore = config.storeFile
        val alias = config.keyAlias
        val storePassword = config.storePassword.toCharArray()
        val keyPassword = config.keyPassword.toCharArray()
        try {
            val store = KeyStore.getInstance(keystore, storePassword)
            if (store.getKey(alias, keyPassword) !is PrivateKey) {
                throw GradleException("Signing alias '$alias' does not contain a private key.")
            }
            if (store.getCertificateChain(alias).isNullOrEmpty()) {
                throw GradleException("Signing alias '$alias' does not contain a certificate chain.")
            }
            logger.lifecycle("Release signing verified: keystore, private key and certificate chain are readable.")
        } finally {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
