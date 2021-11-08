import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dockerCommand
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dotnetPublish
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dotnetTest
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.exec
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.script
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

version = "2021.2"

project {

    buildType(Build)

    features {
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

        cleanCheckout = true
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
        dotnetPublish {
            name = "Dotnet Publish self contained"
            projects = "RandomQuotes/RandomQuotes.csproj"
            framework = "netcoreapp3.1"
            configuration = "Release"
            outputDir = ".pack"
            args = "-r linux-x64 --self-contained=true"
            sdk = "3.1"
            param("dotNetCoverage.dotCover.home.path", "%teamcity.tool.JetBrains.dotCover.CommandLineTools.DEFAULT%")
        }
        step {
            name = "Octo Pack"
            type = "octopus.pack.package"
            param("octopus_packageoutputpath", ".pkg")
            param("octopus_packageid", "RandomQuotes")
            param("octopus_packageversion", "%build.number%")
            param("octopus_packageformat", "NuPkg")
            param("octopus_packagesourcepath", ".pack")
        }
        script {
            name = "Cloudsmith push nuget package"
            workingDir = ".pkg"
            scriptContent = """
                pip install --upgrade pip 2>&1
                pip install --upgrade cloudsmith-cli 2>&1
                cloudsmith push nuget diligent/randomquotes -k %env.CloudsmithApiKey% -F pretty RandomQuotes.%build.counter%.nupkg
            """.trimIndent()
            formatStderrAsError = true
            param("org.jfrog.artifactory.selectedDeployableServer.downloadSpecSource", "Job configuration")
            param("org.jfrog.artifactory.selectedDeployableServer.useSpecs", "false")
            param("org.jfrog.artifactory.selectedDeployableServer.uploadSpecSource", "Job configuration")
        }
        dockerCommand {
            name = "Docker Build"
            enabled = false
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
            enabled = false
            commandType = other {
                subCommand = "tag"
                commandArgs = "docker.cloudsmith.io/diligent/randomquotes/randomquotes:%build.number% docker.cloudsmith.io/diligent/randomquotes/random:latest"
            }
        }
        dockerCommand {
            name = "Docker Push"
            enabled = false
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
            param("octopus_host", "https://diligent-test.octopus.app")
            param("octopus_project_name", "RandomQuotes")
            param("octopus_deployto", "Test")
            param("secure:octopus_apikey", "credentialsJSON:e31c3b29-edaf-4970-aa62-199d715e20d1")
            param("octopus_releasenumber", "%build.number%")
        }
    }

    triggers {
        vcs {
            triggerRules = """
                +:.
                -:.teamcity/**
                -:.octopus/**
            """.trimIndent()
            branchFilter = ""
        }
    }

    features {
        dockerSupport {
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_7"
            }
        }
    }
})
