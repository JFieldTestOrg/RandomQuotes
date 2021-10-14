import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dockerCommand
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dotnetTest
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.exec
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
        dockerRegistry {
            id = "PROJECT_EXT_7"
            name = "Cloudsmith Docker"
            url = "https://docker.cloudsmith.io"
            userName = "dil-svc-pkg-mgr+writer@diligent.com"
            password = "credentialsJSON:33cad918-92d5-4c99-8864-1ed076e18cbe"
        }
    }
}

object Build : BuildType({
    name = "Build"

    publishArtifacts = PublishMode.SUCCESSFUL

    params {
        param("env.Git_Branch", "${DslContext.settingsRoot.paramRefs.buildVcsBranch}")
    }

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        exec {
            name = "GItversion"
            path = "gitversion"
            arguments = "/output buildserver /updateassemblyinfo true"
            formatStderrAsError = true
            param("org.jfrog.artifactory.selectedDeployableServer.downloadSpecSource", "Job configuration")
            param("org.jfrog.artifactory.selectedDeployableServer.useSpecs", "false")
            param("org.jfrog.artifactory.selectedDeployableServer.uploadSpecSource", "Job configuration")
        }
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
                namesAndTags = "docker.cloudsmith.io/diligent/randomquotes/randomquotes:%build.number%"
                commandArgs = "--pull"
            }
            param("dockerImage.platform", "linux")
        }
        dockerCommand {
            name = "Docker tag latest"
            commandType = other {
                subCommand = "tag"
                commandArgs = "docker.cloudsmith.io/diligent/randomquotes/randomquotes:%build.number% docker.cloudsmith.io/diligent/randomquotes/random:latest"
            }
        }
        dockerCommand {
            name = "Docker Push"
            commandType = push {
                namesAndTags = "docker.cloudsmith.io/diligent/randomquotes/randomquotes:%build.number%"
            }
        }
        step {
            name = "Create and deploy release"
            type = "octopus.create.release"
            enabled = false
            param("octopus_space_name", "Spaces-1")
            param("octopus_waitfordeployments", "true")
            param("octopus_version", "3.0+")
            param("octopus_host", "diligent-test.octopus.app")
            param("octopus_project_name", "RandomQuotes")
            param("octopus_deployto", "Development")
            param("secure:octopus_apikey", "credentialsJSON:e31c3b29-edaf-4970-aa62-199d715e20d1")
            param("octopus_releasenumber", "%build.number%")
        }
    }

    triggers {
        vcs {
            triggerRules = """
                -:.teamcity/**
                -:.octopus/**
            """.trimIndent()
            branchFilter = ""
        }
    }

    features {
        dockerSupport {
            cleanupPushedImages = true
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_7"
            }
        }
    }
})
