plugins { application }

// POC 5: the free-text agent. Spring AI drives the LLM loop (Amazon Bedrock, Converse API) and
// calls Grocery Core operations as tools; the Telegram adapter comes from the chat module.
dependencies {
    "implementation"(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    "implementation"(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    "implementation"(project(":mealie"))
    "implementation"(project(":chat"))
    "implementation"("org.springframework.boot:spring-boot-starter")
    "implementation"("org.springframework.ai:spring-ai-starter-model-bedrock-converse")
    "implementation"("org.telegram:telegrambots-longpolling:10.3.0")
    "implementation"("org.telegram:telegrambots-client:10.3.0")

}

application { mainClass = "grocery.agent.AgentApplication" }

// Run from the repository root so `.env` resolves as in the README.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir.parentFile
    jvmArgs("-Dstdout.encoding=UTF-8")
}

// The chat module brings slf4j-simple for its own demo; Spring Boot logs through Logback.
configurations.named("runtimeClasspath") { exclude(group = "org.slf4j", module = "slf4j-simple") }
configurations.named("testRuntimeClasspath") { exclude(group = "org.slf4j", module = "slf4j-simple") }
