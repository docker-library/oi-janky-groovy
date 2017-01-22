node {
	stage('Checkout') {
		checkout(
			poll: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/tianon/bad-ideas.git',
					name: 'origin',
					refspec: '+refs/heads/master:refs/remotes/origin/master',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[$class: 'CloneOption', honorRefspec: true, noTags: true],
					[$class: 'RelativeTargetDirectory', relativeTargetDir: 'archive'],
					[$class: 'CleanCheckout'],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/official-images.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[$class: 'RelativeTargetDirectory', relativeTargetDir: 'oi'],
					[$class: 'CleanCheckout'],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
		sshagent(['docker-library-bot']) {
			stage('Archive') {
				sh('''
					export BASHBREW_LIBRARY="$PWD/oi/library"

					cd archive
					./all-bad.sh
				''')
			}
		}
	}
}
