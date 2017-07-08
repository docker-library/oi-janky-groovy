properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
])

suites = [
	'jessie',
	'stretch',

	'xenial',
	'zesty',
]

node {
	stage('Checkout') {
		checkout(
			poll: true,
			changelog: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/tianon/docker-deb-vendored.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'docker-tianon',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		sh '''
			rm -rf docker-tianon_*
		'''
	}

	stage('Pull') {
		sh '''
			docker pull tianon/sbuild
		'''
	}

	stage('Fetch') {
		sh '''
			docker run -i --rm -v "$PWD":/work -w /work/docker-tianon -u "$(id -u):$(id -g)" tianon/sbuild bash -c '
				set -Eeuo pipefail
				set -x
				uscan --download-current-version --rename --destdir ..
				extract-origtargz
			'
		'''
	}

	for (suite in suites) {
		stage(suite) {
			withEnv([
				'SUITE=' + suite,
			]) {
				sh '''
					git -C docker-tianon checkout -- debian/changelog
					docker run -i --rm -v "$PWD":/work -w /work/docker-tianon -u "$(id -u):$(id -g)" -e SUITE tianon/sbuild bash -c '
						set -Eeuo pipefail
						set -x
						version="$(dpkg-parsechangelog -SVersion)~${SUITE}0" # "17.06.0-0~tianon1~jessie0"
						dch \\
							--force-bad-version \\
							--newversion "$version" \\
							--force-distribution \\
							--distribution "$SUITE" \\
							"Automated Jenkins build ($SUITE)"
						dpkg-buildpackage -uc -us -S -nc
					'
				'''
			}
		}
	}

	stage('Archive') {
		archiveArtifacts(
			artifacts: 'docker-tianon_*',
			fingerprint: true,
		)
	}
}
