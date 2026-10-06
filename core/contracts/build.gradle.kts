// Data shapes the modules hand to each other. In the POC stage they travel as JSON files;
// in the integrated app they are plain method arguments.
dependencies {
    "api"("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    "api"("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.0")
}
