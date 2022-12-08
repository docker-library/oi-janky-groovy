// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
env.PUSH_TO_NAMESPACE = 'library'

env.BASHBREW_ARCH_NAMESPACES = vars.archNamespaces()

node('put-shared') {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/official-images.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'library/.+',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		checkout(
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/perl-bashbrew.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'perl',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	// make sure "docker login" is localized to this workspace
	env.DOCKER_CONFIG = workspace + '/.docker'
	dir(env.DOCKER_CONFIG) { deleteDir() }

	withCredentials([usernamePassword(
		credentialsId: 'docker-hub-stackbrew',
		usernameVariable: 'DOCKER_USERNAME',
		passwordVariable: 'DOCKER_PASSWORD',
	)]) {
		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail
			docker login --username "$DOCKER_USERNAME" --password-stdin <<<"$DOCKER_PASSWORD"
		'''
	}

	withCredentials([string(credentialsId: 'dockerhub-public-proxy', variable: 'DOCKERHUB_PUBLIC_PROXY')]) {
		stage('Put Shared') {
			retry(3) {
				sh '''
					perl/put-multiarch.sh "$PUSH_TO_NAMESPACE/$ACT_ON_IMAGE"
				'''
			}
		}
	}

	// "docker logout"
	dir(env.DOCKER_CONFIG) { deleteDir() }
}
