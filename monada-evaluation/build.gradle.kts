plugins {
    application
}

dependencies {
    implementation(project(":monada-core"))
    implementation(project(":monada-api"))
    testImplementation(project(":monada-storage"))
}

application {
    mainClass.set("com.monada.evaluation.Main")
}
