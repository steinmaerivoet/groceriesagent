plugins { application }

dependencies {
    "api"(project(":contracts"))
    "implementation"("org.telegram:telegrambots-longpolling:10.3.0")
    "implementation"("org.telegram:telegrambots-client:10.3.0")
    "implementation"("org.xerial:sqlite-jdbc:3.53.4.0")
    "runtimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}

application { mainClass = "grocery.chat.Demo" }

// Run demos from the repository root so `.env`, `fixtures/` and `.state/` resolve as in the README.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir.parentFile
    jvmArgs("-Dstdout.encoding=UTF-8")
}
