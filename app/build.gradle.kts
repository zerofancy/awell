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

// AGP keeps signing passwords in its model, which must never enter the configuration cache.
@Suppress("DEPRECATION")
val configurationCacheRequested = gradle.startParameter.isConfigurationCacheRequested
if (keyringSigningEnabled && configurationCacheRequested) {
    throw GradleException("Keyring signing requires --no-configuration-cache to avoid caching passwords.")
}

fun requiredSigningProperty(name: String): String = signingProperty(name)
    ?.takeIf { it.isNotBlank() }
    ?: throw GradleException(
        "Set $name in local.properties or with -P$name. See docs/keyring-signing.html; " +
            "use -PkeyringSigning=false while initializing credentials."
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

    if (keyringSigningEnabled) {
        val keystore = rootProject.file(requiredSigningProperty("keyringStoreFile"))
        if (!keystore.isFile) {
            throw GradleException("Signing keystore does not exist: $keystore")
        }
        val alias = requiredSigningProperty("keyringKeyAlias")
        signingConfigs.create("keyringRelease") {
            storeFile = keystore
            keyAlias = alias
            storePassword = signingPassword("android_store_password")
            keyPassword = signingPassword("android_key_password")
        }
    }

    buildTypes {
        release {
            if (keyringSigningEnabled) {
                signingConfig = signingConfigs.getByName("keyringRelease")
            }
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

val releaseSigningConfig = android.buildTypes.getByName("release").signingConfig
tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Checks the release keystore, passwords, private key and certificate chain without building."
    notCompatibleWithConfigurationCache("Reads local signing credentials")
    doLast {
        val config = releaseSigningConfig
            ?: throw GradleException("Release signing is not configured. Set keyringSigning=true to enable it.")
        val keystore = config.storeFile ?: throw GradleException("Signing keystore is not configured.")
        val alias = config.keyAlias ?: throw GradleException("Signing key alias is not configured.")
        val storePassword = config.storePassword?.toCharArray()
            ?: throw GradleException("Keystore password is not configured.")
        val keyPassword = config.keyPassword?.toCharArray()
            ?: throw GradleException("Private key password is not configured.")
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
