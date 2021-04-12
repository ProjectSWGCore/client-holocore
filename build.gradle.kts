plugins {
	idea
	java
	kotlin("jvm")
}

val javaVersion: String by ext
val javaMajorVersion: String by ext
val kotlinTargetJdk: String by ext

java {
	modularity.inferModulePath.set(true)
}

idea {
	targetVersion = javaVersion
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
        java.outputDir = File(java.outputDir.toString().replace("\\${File.separatorChar}java", ""))
		
		dependencies {
			implementation(kotlin("stdlib"))
			implementation(project(":pswgcommon"))
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
		jvmTarget = kotlinTargetJdk
	}
}
