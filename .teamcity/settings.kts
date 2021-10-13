import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dockerCommand
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dotnetTest
import jetbrains.buildServer.configs.kotlin.v2019_2.projectFeatures.dockerRegistry
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.vcs

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2021.1"

project {

    buildType(Build)

    features {
        dockerRegistry {
            id = "PROJECT_EXT_11"
            name = "Docker-Local"
            url = "http://docker-local.devops.ow.npres.local"
            userName = "svc-automation"
            password = "credentialsJSON:abfa4e5f-4057-43e3-aff1-583d81bfcb18"
        }
    }
}

object Build : BuildType({
    name = "Build"

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        dotnetTest {
            name = "DotNet Test"
            param("dotNetCoverage.dotCover.home.path", "%teamcity.tool.JetBrains.dotCover.CommandLineTools.DEFAULT%")
        }
        dockerCommand {
            name = "Docker Build"
            commandType = build {
                source = file {
                    path = "RandomQuotes/Dockerfile"
                }
                namesAndTags = "docker-local.devops.ow.npres.local/randomquotes:1.0.%build.counter%"
                commandArgs = "--pull"
            }
        }
        dockerCommand {
            name = "Docker Push"
            commandType = push {
                namesAndTags = "docker-local.devops.ow.npres.local/randomquotes:1.0.%build.counter%"
            }
        }
        step {
            name = "Create and deploy release"
            type = "octopus.create.release"
            param("octopus_space_name", "Spaces-22")
            param("octopus_version", "3.0+")
            param("octopus_host", "https://octopus.corp.diligent.com")
            param("octopus_project_name", "Random Quotes")
            param("octopus_deployto", "Development")
            param("secure:octopus_apikey", "credentialsJSON:ebf43188-0e85-451a-aefe-1e2c020a09a6")
        }
    }

    triggers {
        vcs {
        }
    }

    features {
        dockerSupport {
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_11"
            }
        }
    }
})