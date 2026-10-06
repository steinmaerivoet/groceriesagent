plugins { application }

dependencies { "api"(project(":contracts")) }

application { mainClass = "grocery.mealie.Demo" }

// Run demos from the repository root so `.env` and `fixtures/` resolve as in the README.
tasks.named<JavaExec>("run") {
    workingDir = rootDir.parentFile
    jvmArgs("-Dstdout.encoding=UTF-8")
}
