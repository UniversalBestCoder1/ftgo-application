import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

class FtgoServicePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        project.apply(plugin: 'org.springframework.boot')
    	project.apply(plugin: "io.spring.dependency-management")

        project.dependencyManagement {
            imports {
                mavenBom "org.springframework.cloud:spring-cloud-contract-dependencies:${project.ext.springCloudContractDependenciesVersion}"
                mavenBom "io.eventuate.platform:eventuate-platform-dependencies:${project.ext.eventuatePlatformVersion}"
            }
        }

        project.configurations.all {
            exclude group: 'org.apache.logging.log4j'
            exclude group: 'log4j'
        }

        project.dependencies {
            // Spring Boot 3: Spring Cloud Sleuth replaced by Micrometer Tracing
            implementation 'io.micrometer:micrometer-tracing-bridge-brave'
            implementation 'io.zipkin.reporter2:zipkin-reporter-brave'

            implementation(platform("io.eventuate.platform:eventuate-platform-dependencies:${project.ext.eventuatePlatformVersion}"))
        }

    }
}
