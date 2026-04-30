plugins {
    application
}

dependencies {
    implementation(project(":monada-core"))
    implementation(project(":monada-api"))
}

application {
    mainClass.set("com.monada.evaluation.Main")
}
