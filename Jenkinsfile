// =============================================================================
// Delivery pipeline for the banking transaction processor.
//
// This repository holds TWO independent Maven projects, not one aggregator:
//
//   ./pom.xml                     -> ledger-service   (src at the repo root)
//   ./compliance-service/pom.xml  -> compliance-service
//
// So every build stage has to be told about both. That is the cost of keeping
// the services independently deployable while sharing a repository; a single
// reactor build would be one command, but would also mean one version number
// and one release for two services that should be able to move separately.
//
// Reviewed but never executed: there was no Jenkins available while writing it,
// and no Docker on the machine, so the image stages in particular are a reviewed
// starting point rather than a proven one.
//
// Assumes a Linux agent with `docker` available, and Jenkins tool installations
// named 'jdk-17' and 'maven-3.9'. Adjust to the controller's actual names.
// =============================================================================

pipeline {

    agent any

    tools {
        jdk 'jdk-17'
        maven 'maven-3.9'
    }

    options {
        // A hung build holds an agent indefinitely; an hour is generous for a
        // suite that takes about three minutes.
        timeout(time: 60, unit: 'MINUTES')

        // Bounded history, for the same reason logs are bounded: unbounded
        // retention eventually fills the controller's disk.
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))

        timestamps()

        // Two builds of the same branch writing to one workspace would corrupt
        // each other's target directories.
        disableConcurrentBuilds()

        skipStagesAfterUnstable()
    }

    environment {
        // Tagged with the commit, never with 'latest'. A moving tag makes "which
        // build is in production?" unanswerable, and makes a rollback a guess.
        IMAGE_TAG = "${env.GIT_COMMIT ? env.GIT_COMMIT.take(12) : env.BUILD_NUMBER}"

        REGISTRY = 'registry.example.internal/wealthcdio'
        LEDGER_IMAGE = "${REGISTRY}/ledger-service"
        COMPLIANCE_IMAGE = "${REGISTRY}/compliance-service"

        // Batch mode: no ANSI progress spam, and no interactive prompts to hang on.
        MAVEN_ARGS = '-B -e -ntp'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                script {
                    echo "Building commit ${env.GIT_COMMIT ?: 'unknown'} on ${env.BRANCH_NAME ?: 'unknown branch'}"
                }
            }
        }

        // Both projects are built at once. They share no code and write to
        // different target directories, so there is nothing to serialise - and
        // the compliance service's suite is short enough that it costs nothing.
        stage('Build and test') {
            parallel {

                stage('ledger-service') {
                    steps {
                        sh "mvn ${MAVEN_ARGS} verify"
                    }
                    post {
                        always {
                            // allowEmptyResults false on purpose: a run that
                            // produced no test results has not passed, it has
                            // failed to test anything, and that must not be
                            // reported as green.
                            junit allowEmptyResults: false,
                                  testResults: 'target/surefire-reports/*.xml'
                        }
                    }
                }

                stage('compliance-service') {
                    steps {
                        sh "mvn ${MAVEN_ARGS} -f compliance-service/pom.xml verify"
                    }
                    post {
                        always {
                            junit allowEmptyResults: false,
                                  testResults: 'compliance-service/target/surefire-reports/*.xml'
                        }
                    }
                }
            }
        }

        stage('Archive jars') {
            steps {
                archiveArtifacts artifacts: 'target/*.jar, compliance-service/target/*.jar',
                                 fingerprint: true,
                                 onlyIfSuccessful: true
            }
        }

        // Images are only built for the main branch. Building one per pull
        // request fills a registry with artefacts nobody deploys, and the tests
        // above are what a pull request actually needs.
        stage('Container images') {
            when {
                branch 'main'
            }
            steps {
                // Each image has its own build context: the repo root for the
                // ledger service, the subdirectory for compliance. See the
                // .dockerignore files for what is kept out.
                sh "docker build -t ${LEDGER_IMAGE}:${IMAGE_TAG} ."
                sh "docker build -t ${COMPLIANCE_IMAGE}:${IMAGE_TAG} compliance-service"
            }
        }

        stage('Push images') {
            when {
                branch 'main'
            }
            steps {
                // Credentials come from Jenkins, never from the repository. The
                // password is piped on stdin rather than passed as an argument,
                // so it cannot be read from the agent's process list.
                withCredentials([usernamePassword(credentialsId: 'container-registry',
                                                  usernameVariable: 'REGISTRY_USER',
                                                  passwordVariable: 'REGISTRY_PASSWORD')]) {
                    sh '''
                        set -e
                        echo "$REGISTRY_PASSWORD" | docker login "$REGISTRY" \
                            --username "$REGISTRY_USER" --password-stdin
                        docker push "$LEDGER_IMAGE:$IMAGE_TAG"
                        docker push "$COMPLIANCE_IMAGE:$IMAGE_TAG"
                        docker logout "$REGISTRY"
                    '''
                }
            }
        }

        stage('Deploy to staging') {
            when {
                branch 'main'
            }
            steps {
                // Separate charts per service, matching the architecture: either
                // can be released without the other. --atomic so a failed
                // rollout is rolled back rather than left half-applied, and
                // --wait so the stage fails when the pods never become ready
                // instead of reporting success and leaving it broken.
                withCredentials([file(credentialsId: 'kubeconfig-staging', variable: 'KUBECONFIG')]) {
                    sh """
                        set -e
                        helm upgrade --install compliance-service deploy/helm/compliance-service \
                            --namespace ledger-staging --create-namespace \
                            --set image.repository=${COMPLIANCE_IMAGE} \
                            --set image.tag=${IMAGE_TAG} \
                            --atomic --wait --timeout 5m

                        helm upgrade --install ledger-service deploy/helm/ledger-service \
                            --namespace ledger-staging \
                            --set image.repository=${LEDGER_IMAGE} \
                            --set image.tag=${IMAGE_TAG} \
                            --atomic --wait --timeout 5m
                    """
                }
                // Compliance goes first. If the ledger service arrived first it
                // would briefly refuse every transfer with LDG-3002 - correct
                // behaviour, but an avoidable window.
            }
        }

        stage('Deploy to production') {
            when {
                branch 'main'
            }
            steps {
                // A human decides when money-moving code reaches production.
                // Automating this stage is a fine goal, but it needs smoke tests
                // and an automated rollback trigger first, and neither exists yet.
                timeout(time: 30, unit: 'MINUTES') {
                    input message: "Deploy ${IMAGE_TAG} to production?", ok: 'Deploy'
                }
                echo "Production deployment is not configured in this repository."
                echo "It would mirror the staging stage against the production kubeconfig."
            }
        }
    }

    post {
        always {
            // Agents are shared, and a Maven target directory left behind is
            // both wasted disk and a source of stale-artefact confusion.
            cleanWs()
        }
        failure {
            echo "Build failed. Surefire reports for both services are attached to this build."
        }
    }
}

// Deliberately absent, and worth naming rather than leaving as a gap:
//
// - Dependency vulnerability scanning (OWASP dependency-check, Trivy on the
//   built images). Both belong in a banking pipeline; neither is added here
//   because a scanner configured without a triage process just produces a
//   failing build nobody can act on.
// - Static analysis / coverage gates (SonarQube, JaCoCo thresholds). The suite
//   is thorough, but "thorough" is an opinion until something measures it.
// - Smoke tests after deployment, which is the missing piece that would let the
//   production stage stop asking a human.
