plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
}

gradlePlugin {
    plugins {
        create("flatpak-plugin") {
            id = "de.connect2x.tammy.plugins.flatpak"
            implementationClass = "de.connect2x.tammy.plugins.flatpak.FlatpakPlugin"
        }
    }
}