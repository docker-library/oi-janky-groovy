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
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'd',
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'UserIdentity',
						name: 'Docker Library Bot',
						email: 'github+dockerlibrarybot@infosiftr.com',
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

	stage('Update') {
		sh('''
			export BASHBREW_LIBRARY="$PWD/oi/library"

			cd d
			./update.sh
		''')
	}

	stage('Commit') {
		sh('''
			cd d
			git add . || :
			git commit -m 'Run update.sh' || :
		''')
	}

	stage('Push') {
		sshagent(['docker-library-bot']) {
			sh('''
				cd d
				git push origin HEAD:master
			''')
		}
	}

	stage('Deploy') {
		withCredentials([[
			$class: 'UsernamePasswordMultiBinding',
			credentialsId: 'docker-hub-stackbrew',
			usernameVariable: 'USERNAME',
			passwordVariable: 'PASSWORD',
		]]) {
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
