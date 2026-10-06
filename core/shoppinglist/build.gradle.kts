plugins { application }

dependencies {
    "api"(project(":contracts"))
    "implementation"(project(":mealie"))
}

application { mainClass = "grocery.shoppinglist.Demo" }

// Run demos from the repository root so `.env`, `fixtures/` and `.state/` resolve as in the README.
tasks.named<JavaExec>("run") {
    workingDir = rootDir.parentFile
    jvmArgs("-Dstdout.encoding=UTF-8")
}
