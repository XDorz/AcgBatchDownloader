import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.21"
    application
}

group = "github.XDorz"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jsoup:jsoup:1.16.1")
    implementation("cn.hutool:hutool-http:5.8.19")
    implementation("com.alibaba:fastjson:2.0.33")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("net.sf.sevenzipjbinding:sevenzipjbinding-all-windows:16.02-2.01")
    implementation("net.sf.sevenzipjbinding:sevenzipjbinding:16.02-2.01")
    implementation(fileTree("src/main/resources/lib"){
        include("*.jar")
    })
}

//tasks.register("compileJar") {
//    group = "custom"
//    description = "Compile Jar with exclusion"
//
//    val excludedFiles = listOf("CompressMain.kt", "DownloadMain.kt")
//    val sourceDir = file("src/main/kotlin")
//    val outputDir = file("build/libs")
//
//    inputs.dir(sourceDir)
//    outputs.dir(outputDir)
//
//    doLast {
//        project.copy {
//            from(sourceDir) {
//                exclude(excludedFiles)
//            }
//            into(outputDir)
//        }
//    }
//}

//tasks.register("compileJar", Jar::class) {
//    group = "build"
//    description = "compile to jar tool"
//
//    val excludedFiles = listOf("CompressMain.kt", "DownloadMain.kt")
//    val sourceDir = file("src/main/kotlin")
//    val outputDir = file("build/libs")
//
//    inputs.dir(sourceDir)
//    outputs.dir(outputDir)
//
//    exclude(excludedFiles)
//
//    doLast {
//        // 可以添加其他自定义逻辑
//        // 调用 Jar 任务的默认行为
//        tasks.jar
//    }
//}

tasks.jar{
    archiveFileName.set("FanboxDownloader_$version.jar")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    configurations["compileClasspath"].forEach { file: File ->
        from(zipTree(file.absoluteFile))
    }

    val sourcesMain = sourceSets.main.get()
    sourcesMain.allSource.forEach { println("add from sources: ${it.name}") }
    from(sourcesMain.output)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}