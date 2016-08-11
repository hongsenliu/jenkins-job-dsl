package devops

import org.yaml.snakeyaml.Yaml
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_LOG_ROTATOR
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_GITHUB_BASEURL


/*
Example secret YAML file used by this script

    authUser : name-of-auth-user-for-stage
    authPass : password-of-auth-user-for-stage
    loginEmail : email-for-stage-login
    loginPass : pass-for-stage-login
    courseOrg: organization-of-course-for-test
    courseNumber: course-number-of-course-for-test
    courseRun: course-run-number-of-course-for-test
    courseDisp: course-display-name-of-course-for-test
    repoName : repo-name
    open : true/false
    email: me@email.com
    platformUrl : platform-github-url-segment
    jobName : job-name-to-be-created
    platformCredential: n/a
*/

/* stdout logger */
/* use this instead of println, because you can pass it into closures or other scripts. */
Map config = [:]
Binding bindings = getBinding()
config.putAll(bindings.getVariables())
PrintStream out = config['out']


/* Map to hold the k:v pairs parsed from the secret file */
Map secretMap = [:]
try {
    out.println('Parsing secret YAML file')
    /* Parse k:v pairs from the secret file referenced by secretFileVariable */
    String contents = new File("${EDX_END_TO_END_TESTS}").text
    Yaml yaml = new Yaml()
    secretMap = yaml.load(contents)
    out.println('Successfully parsed secret YAML file')
}
catch (any) {
    out.println('Jenkins DSL: Error parsing secret YAML file')
    out.println('Exiting with error code 1')
    return 1
}

/* Iterate over the job configurations */
secretMap.each { jobConfigs ->

    Map jobConfig = jobConfigs.getValue()

    /* Test secret contains all necessary keys for this job */
    /* TODO: Use/Build a more robust test framework for this */
    assert jobConfig.containsKey('authUser')
    assert jobConfig.containsKey('authPass')
    assert jobConfig.containsKey('loginEmail')
    assert jobConfig.containsKey('loginPass')
    assert jobConfig.containsKey('courseOrg')
    assert jobConfig.containsKey('courseNumber')
    assert jobConfig.containsKey('courseRun')
    assert jobConfig.containsKey('courseDisp')
    assert jobConfig.containsKey('repoName')
    assert jobConfig.containsKey('open')
    assert jobConfig.containsKey('email')
    assert jobConfig.containsKey('platformUrl')
    assert jobConfig.containsKey('jobName')
    assert jobConfig.containsKey('platformCredential')

    job(jobConfig['jobName']) {

        /* For non-open jobs, enable project based security */
        if (!jobConfig['open'].toBoolean()) {
            authorization {
                blocksInheritance(true)
                permissionAll('edx')
            }
        }

        properties {
              githubProjectUrl(JENKINS_PUBLIC_GITHUB_BASEURL + jobConfig['platformUrl'])
        }

        logRotator JENKINS_PUBLIC_LOG_ROTATOR() //Discard build after 14 days

        concurrentBuild() //concurrent builds can happen

        label('master') //restrict to flow-worker-bokchoy

        checkoutRetryCount(5)

        environmentVariables {
            env('BASIC_AUTH_USER', jobConfig['authUser'])
            env('BASIC_AUTH_PASSWORD', jobConfig['authPass'])
            env('USER_LOGIN_EMAIL', jobConfig['loginEmail'])
            env('USER_LOGIN_PASSWORD', jobConfig['loginPass'])
            env('COURSE_ORG', jobConfig['courseNumber'])
            env('COURSE_NUMBER', jobConfig['authPass'])
            env('COURSE_RUN', jobConfig['courseRun'])
            env('COURSE_DISPLAY_NAME', jobConfig['courseDisp'])
        }

        scm {

           git { //using git on the branch and url, clone, clean before checkout
                remote {
                    url(JENKINS_PUBLIC_GITHUB_BASEURL + jobConfig['platformUrl'] + '.git')
                    if (!jobConfig['open'].toBoolean()) {
                        credentials(jobConfig['platformCredential'])
                    }
                }
                branch('*/master')
                browser()
            }
        }

        steps {
            shell("jenkins/end_to_end_tests.sh")
        }

        triggers { //triggers weekly
            scm('@weekly')
        }


        wrappers {
            timeout {
               absolute(75)
           }
           timestamps()
           colorizeOutput('gnome-terminal')
        }


        publishers {
          archiveArtifacts('reports/*.xml')
          mailer(jobConfig['email'])
      }
    }
}
