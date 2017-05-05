node {
	stage('Checkout') {
		checkout(
			poll: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:docker-library/repo-info.git',
					credentialsId: 'docker-library-bot',
					name: 'origin',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ri'],
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

	ansiColor('xterm') {
		stage('Update') {
			sh('''
				export BASHBREW_LIBRARY="$PWD/oi/library"

				cd ri
				./update-remote.sh
			''')
		}
	}

	stage('Commit') {
		sh('''
			cd ri

			git config user.name 'Docker Library Bot'
			git config user.email 'github+dockerlibrarybot@infosiftr.com'

			git add repos || :
			git commit -m 'Run update-remote.sh' || :
		''')
	}

	stage('Push') {
		sshagent(['docker-library-bot']) {
			sh('''
				cd ri

				# try catching up since this job takes so long to run
				git checkout -- .
				git clean -dfx .
				git pull --rebase origin master || :

				# fire away!
				git push origin HEAD:master
			''')
		}
	}
}
