plugins {
    id("com.android.application")
}

android {
    enableKotlin = false
    namespace = "io.github.vvb2060.pxeboot"
    defaultConfig {
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            vcsInfo.include = false
            signingConfig = signingConfigs["debug"]
            optimization {
                enable = true
                keepRules {
                    ignoreFromAllExternalDependencies = true
                    includeDefault = false
                }
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            excludes += "**"
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    dependenciesInfo {
        includeInApk = false
    }
}


androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
        tasks.register<JavaExec>("localRun") {
            description = "Run the application locally"
            group = "application"
            mainClass.set("io.github.vvb2060.pxeboot.server.App")
            val javaCompileTask = tasks.named<JavaCompile>("compile${variantCapped}JavaWithJavac")
            classpath = files(javaCompileTask.map { it.destinationDirectory })
            standardInput = System.`in`
            dependsOn(javaCompileTask)
        }
    }
}
