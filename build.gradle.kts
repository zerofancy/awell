plugins {
    alias(libs.plugins.keyring)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

keyring {
    secret("android_store_password", "signStorePassword", "SIGN_STORE_PASSWORD")
    secret("android_key_password", "signKeyPassword", "SIGN_KEY_PASSWORD")
}
