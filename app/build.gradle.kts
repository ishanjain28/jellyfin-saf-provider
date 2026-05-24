plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
	id("com.google.devtools.ksp")
}

android {
	namespace = "me.ishan.jellyfin_saf"
	compileSdk {
		version = release(36) {
			minorApiLevel = 1
		}
	}
	buildFeatures {
		resValues = true
	}
	
	defaultConfig {
		applicationId = "me.ishan.jellyfin_saf"
		minSdk = 26
		targetSdk = 36
		versionName = "0.0.6"
		versionCode = 6
		
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}
	
	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
			)
			signingConfig = signingConfigs.getByName("debug")
			resValue("string", "app_version", "${defaultConfig.versionName}")
		}
		debug {
			versionNameSuffix = "-debug"
			resValue("string", "app_version", "${defaultConfig.versionName}-debug")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	buildFeatures {
		compose = true
	}
}

dependencies {
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.room.ktx)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.okhttp)
	implementation(libs.moshi)
	implementation(libs.jellyfin.core)
	implementation(libs.jellyfin.api)
	implementation(libs.jellyfin.model)
	implementation(libs.slf4j.android)
	implementation(libs.slf4j.api)
	ksp("androidx.room:room-compiler:2.8.4")
	
	testImplementation(libs.junit)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
	debugImplementation(libs.androidx.compose.ui.test.manifest)
	debugImplementation(libs.androidx.compose.ui.tooling)
}