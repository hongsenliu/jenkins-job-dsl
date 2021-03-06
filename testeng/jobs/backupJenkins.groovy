package testeng

import org.yaml.snakeyaml.Yaml
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_HIPCHAT

Map config = [:]
Binding bindings = getBinding()
config.putAll(bindings.getVariables())
PrintStream out = config['out']

try {
    out.println('Parsing secret YAML file')
    /* Parse k:v pairs from the secret file referenced by secretFileVariable */
    String contents = new File("${BACKUP_JENKINS_SECRET}").text
    Yaml yaml = new Yaml()
    secretMap = yaml.load(contents)
    out.println('Successfully parsed secret YAML file')
}
catch (any) {
    out.println('Jenkins DSL: Error parsing secret YAML file')
    out.println('Exiting with error code 1')
    return 1
}

/*
Example secret YAML file used by this script
Config:
    jenkinsInstance : test
    volumeId : vol-123
    region : us-west.1
    hipchat : hipchat token
    email : email@address.com
*/

/* Iterate over the job configurations */
secretMap.each { jobConfigs ->

    Map jobConfig = jobConfigs.getValue()

    assert jobConfig.containsKey('jenkinsInstance')
    assert jobConfig.containsKey('volumeId')
    assert jobConfig.containsKey('region')
    assert jobConfig.containsKey('hipchat')
    assert jobConfig.containsKey('email')

    job("backup-${jobConfig['jenkinsInstance']}-jenkins") {
        
        // private job
        authorization {
            blocksInheritance(true)
            permissionAll('edx')
        }

        description('A regularly run job for creating snapshots of build jenkins master ' +
                    ' and uploading them to Amazon s3')
        // Keep logs longer than normal jenkins jobs
        logRotator {
            numToKeep(50)
        }
        concurrentBuild(false)
        label("backup-runner")

        // Configure the Exclusive Execution plugin, to reduce the amount of things in memory
        // during snapshotting
        configure { project ->
            project / buildWrappers << 'hudson.plugins.execution.exclusive.ExclusiveBuildWrapper' {
                skipWaitOnRunningJobs false
            }
        }

        // Run snapshotting script once a day, at 1:00 AM
        triggers {
            cron('0 1 * * *')
        }

        wrappers {
            timeout {
                absolute(20)
                abortBuild()
            }
            timestamps()
            colorizeOutput('xterm')
        }

        environmentVariables {
            env('AWS_DEFAULT_REGION', jobConfig['region'])
        }
        
        // Sync currently paged files to disk
        String script = "set -o pipefail\n"
        script += "sync\n"
        // This might seem overkill, but in case the pip requirements change, read them from
        // the requirements file in the workspace
        readFileFromWorkspace('testeng/resources/requirements.txt').split("\n").each { line ->
            script += "pip install --exists-action w ${line}\n"
        }
        script += "aws ec2 create-snapshot --volume-id ${jobConfig['volumeId']} --description 'Automatic ${jobConfig['jenkinsInstance']} jenkins snapshot' |tee \${WORKSPACE}/snapshot-out"
        steps {
            virtualenv {
                clear()
                name('venv')
                nature('shell')
                command(script)
            }
        }

        publishers {
            // alert team of failures via hipchat & email
            hipChat JENKINS_PUBLIC_HIPCHAT.call(jobConfig['hipchat'])
            mailer(jobConfig['email'])
            // fail the build if the snapshot command does not correctly trigger a snapshot
            // requires "textFinder plugin"
            textFinder('"State": "(pending|completed)"', 'snapshot-out', false, true, false)
        }
    }
    
}
