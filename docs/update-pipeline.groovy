properties([
	buildDiscarder(logRotator(daysToKeepStr: '14')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H * * * *')
	]),
])

node {
	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:docker-library/docs.git',
					credentialsId: 'docker-library-bot',
					name: 'origin',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'd',
					],
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
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'CleanCheckout',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') {
		stage('Update') {
			sh('''
				export BASHBREW_LIBRARY="$PWD/oi/library"

				cd d
				./update.sh
			''')
		}
	}

	stage('Commit') {
		sh('''
			cd d

			git config user.name 'Docker Library Bot'
			git config user.email 'github+dockerlibrarybot@infosiftr.com'

			git add . || :
			git commit -m 'Run update.sh' || :
		''')
	}

	sshagent(['docker-library-bot']) {
		stage('Push') {
			sh('''
				cd d
				git push origin HEAD:master
			''')
		}
	}

	ansiColor('xterm') {
		withCredentials([[
			$class: 'UsernamePasswordMultiBinding',
			credentialsId: 'docker-hub-stackbrew',
			usernameVariable: 'USERNAME',
			passwordVariable: 'PASSWORD',
		]]) {
			stage('Deploy') {
				sh('''
					cd d
					docker build --pull -t docker-library-docs -q .
					test -t 1 && it='-it' || it='-i'
					set +x
					docker run "$it" --rm -e TERM \
						--entrypoint './push.pl' \
						docker-library-docs \
						--username "$USERNAME" \
						--password "$PASSWORD" \
						--batchmode */
				''')
			}
		}
	}
}
