import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dockerCommand
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dotnetPublish
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.dotnetTest
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.exec
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.powerShell
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
    buildType(Test)
    buildType(Push)

    features {
        dockerRegistry {
            id = "PROJECT_EXT_7"
            name = "Cloudsmith Docker"
            url = "https://docker.cloudsmith.io"
            userName = "dil-svc-pkg-mgr+writer@diligent.com"
            password = "credentialsJSON:33cad918-92d5-4c99-8864-1ed076e18cbe"
        }
    }

    cleanup {
        keepRule {
            id = "KEEP_RULE_1"
            keepAtLeast = allBuilds()
            applyToBuilds {
                withStatus = successful()
                withTags = anyOf("released")
            }
            dataToKeep = everything()
            applyPerEachBranch = true
            preserveArtifactsDependencies = true
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
        step {
            type = "MR_GitVersion"
            enabled = false
            executionMode = BuildStep.ExecutionMode.DEFAULT
            param("mr.GitVersion.logFile", "gitversion.log")
            param("mr.GitVersion.branch", "")
            param("mr.GitVersion.output", "buildserver")
            param("mr.GitVersion.updateGitVersion", "false")
            param("mr.GitVersion.outputFile", "")
            param("mr.GitVersion.exec", "")
            param("mr.GitVersion.updateAssemblyInfo", "true")
            param("mr.GitVersion.url", "")
            param("mr.GitVersion.projArgs", "")
            param("mr.GitVersion.password", "credentialsJSON:e4aab887-a0a4-4c5f-a6d1-3cf78d4b8f8c")
            param("mr.GitVersion.proj", "")
            param("mr.GitVersion.username", "")
            param("mr.GitVersion.gitCheckoutDir", "")
            param("mr.GitVersion.execArgs", "")
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
        step {
            name = "Cloudsmith push nuget"
            type = "CloudsmithPushNuget"
            executionMode = BuildStep.ExecutionMode.DEFAULT
            param("CloudsmithRepoName", "randomquotes")
            param("CloudsmithUserName", "dil-svc-pkg-mgr@diligent.com")
            param("PackageName", "RandomQuotes")
            param("CloudsmithOrganisation", "diligent")
            param("PackageDirectory", ".pkg")
            param("CloudsmithApiKey", "%env.CloudsmithApiKey%")
            param("PackageVersion", "%build.number%")
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

object Push : BuildType({
    name = "Push"

    steps {
        powerShell {
            name = "Push"
            minRequiredVersion = "7"
            formatStderrAsError = true
            scriptMode = script {
                content = """
                    pip install --upgrade pip 2>&1
                    pip install --upgrade cloudsmith-cli 2>&1
                    ${'$'}version = ((cloudsmith list packages %CloudsmithOrganisation%/%CloudsmithRepoName% -F pretty_json | convertfrom-json).data | ? Name -eq '%PackageName%').version
                    If (${'$'}version -eq ${'$'}PackageVersion) {
                    	"Package Name and Version already exist, no need to push"
                    } else {
                    	Write-Host "Executing cloudsmith command : cloudsmith push nuget -k %CloudsmithApiKey% -F pretty %CloudsmithOrganisation%/%CloudsmithRepoName% %PackageName%.%PackageVersion%.nupkg"
                    	cloudsmith push nuget -k %CloudsmithApiKey% -F pretty %CloudsmithOrganisation%/%CloudsmithRepoName% %PackageName%.%PackageVersion%.nupkg
                    }
                """.trimIndent()
            }
            param("org.jfrog.artifactory.selectedDeployableServer.downloadSpecSource", "Job configuration")
            param("org.jfrog.artifactory.selectedDeployableServer.useSpecs", "false")
            param("org.jfrog.artifactory.selectedDeployableServer.uploadSpecSource", "Job configuration")
        }
    }
})

object Test : BuildType({
    name = "TestJob"
    description = "Feel free to change this job if you need to test something"

    params {
        param("env.CloudsmithApiKey", "credentialsJSON:26421b48-39c2-428e-803b-6c179d454347")
        param("env.GitVersion.FullSemVer", "22.1.0-SPE-634.53")
        param("CloudsmithApiKey", "%env.CloudsmithApiKey%")
        param("GitVersion.BranchName", "feature/OW-11927")
        param("GitVersion.FullSemVer", "22.1.0-SPE-634.53")
    }

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        powerShell {
            enabled = false
            scriptMode = script {
                content = """
                    try {
                        if (-not(get-command python)) {
                            throw "Python needs to be installed on the build agent, cannot continue!"
                        }
                    }
                    catch {
                        Write-Host Installing pip...
                        pip install --upgrade pip 2>&1 | out-null
                        Write-Host Installing cloudsmith-cli...
                        pip install --upgrade cloudsmith-cli 2>&1 | out-null
                    }
                    
                    ${'$'}packageVersions = "apple"
                    ${'$'}PackageNames = "1,2"
                    
                    ${'$'}PackageVersionList = ${'$'}packageVersions.Split(",")
                    ${'$'}PackageNameList = ${'$'}PackageNames.Split(",")
                    
                    if (${'$'}PackageNameList.Count -ne ${'$'}PackageVersionList.Count) {
                        throw "Mismatch between the specified number of package names and package versions!"
                    }
                    
                    exit 0
                """.trimIndent()
            }
            param("org.jfrog.artifactory.selectedDeployableServer.downloadSpecSource", "Job configuration")
            param("org.jfrog.artifactory.selectedDeployableServer.useSpecs", "false")
            param("org.jfrog.artifactory.selectedDeployableServer.uploadSpecSource", "Job configuration")
        }
        step {
            type = "CloudsmithPushNuget"
            enabled = false
            executionMode = BuildStep.ExecutionMode.DEFAULT

            conditions {
                matches("GitVersion.BranchName", """^master|^feature\/.*|^tags\/.*""")
            }
            param("PackageNames", "AdminPortal,LicencePortal,Entities-Oneworld")
            param("CloudsmithRepoName", "entities")
            param("CloudsmithOrganisation", "diligent")
            param("PackageDirectory", ".pkg")
            param("CloudsmithApiKey", "%env.CloudsmithApiKey%")
            param("PackageVersion", "%env.GitVersion.FullSemVer%")
        }
        step {
            type = "DownloadCodeSigningCert"
            enabled = false
            executionMode = BuildStep.ExecutionMode.DEFAULT
            param("OctoCertProviderApiKey", "%env.OctoCertProviderApiKey%")
            param("CertificateTargetPath", "%system.teamcity.build.workingDir%/CoActWin")
        }
        step {
            type = "CloudSmithTagPackage"
            enabled = false
            executionMode = BuildStep.ExecutionMode.DEFAULT

            conditions {
                matches("teamcity.build.branch", "tags/.*")
            }
            param("Immutable", "true")
            param("PackageNames", "LicencePortal")
            param("PackageVersions", "22.1.0-SPE-634.53")
            param("CloudsmithRepoName", "entities")
            param("PackageTag", "rc")
            param("CloudsmithOrganisation", "diligent")
            param("CloudsmithApiKey", "%env.CloudsmithApiKey%")
            param("PackageFormat", "nuget")
        }
        step {
            type = "DownloadCertificateFromOctopus"
            enabled = false
            executionMode = BuildStep.ExecutionMode.DEFAULT
            param("OctoCertProviderApiKey", "%env.OctoCertProviderApiKey%")
            param("CertificateTargetPath", "%system.teamcity.build.workingDir%")
            param("CertfileFileName", "certificate.pfx")
            param("OctoCertID", "Certificates-201")
            param("OctoSpaceID", "Spaces-1")
        }
        powerShell {
            name = "cloudsmith pkg version test"
            scriptMode = script {
                content = """
                    ${'$'}errors = ''
                    ${'$'}version = Invoke-Expression { ((cloudsmith list packages -k %CloudsmithApiKey% -F pretty_json diligent/entities | convertfrom-json).data | ? Name -eq 'AdminPortal').version } 2> ${'$'}errors
                    Write-Host "Package Name 'AdminPortal' ; Version(s) detected = ${'$'}version"
                    Write-Host "errors: ${'$'}errors"
                """.trimIndent()
            }
            param("org.jfrog.artifactory.selectedDeployableServer.downloadSpecSource", "Job configuration")
            param("org.jfrog.artifactory.selectedDeployableServer.useSpecs", "false")
            param("org.jfrog.artifactory.selectedDeployableServer.uploadSpecSource", "Job configuration")
        }
    }
})
