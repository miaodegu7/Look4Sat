plugins {
    alias(libs.plugins.convention.coreDataPlugin)
}

android {
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    namespace = "com.rtbishop.look4sat.core.data"
}
