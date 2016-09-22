properties([
	buildDiscarder(logRotator(daysToKeepStr: '14')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
	]),
])

node {
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
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'bashbrew/go',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') {
		stage('Build') {
			sh '''
				docker build -t bashbrew --pull -q oi
				docker build -t bashbrew:cross - <<-'EODF'
					FROM bashbrew
					WORKDIR bashbrew/go
					RUN set -ex \\
						&& rm -r bin \\
						&& GOOS=darwin GOARCH=amd64 gb build \\
						&& GOOS=linux GOARCH=amd64 gb build \\
						&& GOOS=windows GOARCH=amd64 gb build \\
						&& ls -l bin
				EODF
				rm -rf bin
				docker run -i --rm bashbrew tar -c bin \\
					| tar -xv
			'''
		}
	}

	stage('Archive') {
		archiveArtifacts(
			artifacts: 'bin/*',
			fingerprint: true,
			onlyIfSuccessful: true,
		)
	}
}
