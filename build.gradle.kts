// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.android.test) apply false
}

project(":ai") {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("androidx.lifecycle:lifecycle-common-java8:2.9.4"))
                .using(module("androidx.lifecycle:lifecycle-common-java8:2.10.0"))
                .because("the local offline cache contains 2.10.0, not the 2.9.4 jar")
            substitute(module("com.google.errorprone:error_prone_annotations:2.15.0"))
                .using(module("com.google.errorprone:error_prone_annotations:2.26.1"))
                .because("the local offline cache contains 2.26.1, not the 2.15.0 jar")
        }
    }
}
