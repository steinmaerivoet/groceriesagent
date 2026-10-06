plugins { application }

dependencies { "api"(project(":contracts")) }

application { mainClass = "grocery.planning.Demo" }

// Run demos from the repository root so `fixtures/` resolves as in the README.
tasks.named<JavaExec>("run") {
    workingDir = rootDir.parentFile
    jvmArgs("-Dstdout.encoding=UTF-8")
}
