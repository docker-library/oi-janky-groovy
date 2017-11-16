properties([
	buildDiscarder(logRotator(daysToKeepStr: '14')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H/15 * * * *'),
	]),
])

node {
	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/yosifkit/official-images-issue-labeler.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'labeler',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	dir('labeler') {
		ansiColor('xterm') {
			stage('Build') {
				sh 'docker build -t docker-library-issue-labeler .'
			}

			withCredentials([[$class: 'StringBinding', credentialsId: 'github-token-docker-library-bot', variable: 'GITHUB_TOKEN']]) {
				stage('Label') {
					sh '''
						set +x
						docker run --rm \\
							docker-library-issue-labeler \\
							--token "$GITHUB_TOKEN" \\
							--owner docker-library \\
							--repo official-images \\
							--state open
					'''
				}
			}
		}
	}
}
