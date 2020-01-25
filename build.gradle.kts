plugins {
	idea
	java
	id("org.javamodularity.moduleplugin")
	kotlin("jvm")
}

idea {
	targetVersion = "12.0.1"
    module {
        inheritOutputDirs = true
    }
}

repositories {
	mavenLocal()
	jcenter()
}

sourceSets {
	main {
		dependencies {
			implementation(project(":pswgcommon"))
			implementation(kotlin("stdlib"))
			api(group="me.joshlarson", name="jlcommon-network", version="1.0.0")
		}
	}
	test {
		dependencies {
			implementation(group="junit", name="junit", version="4.12")
		}
	}
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
	kotlinOptions {
		jvmTarget = "12"
	}
}
