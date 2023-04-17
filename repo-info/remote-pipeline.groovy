properties([
	buildDiscarder(logRotator(daysToKeepStr: '14')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H * * * *'),
	]),
])

node {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: false,
			changelog: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:docker-library/repo-info.git',
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
						relativeTargetDir: 'ri',
					],
					[
						// this repo is huge and takes a long time to pull 😬
						$class: 'CloneOption',
						depth: 1,
						shallow: true,
						timeout: 90,
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
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') { dir('ri') {
		withCredentials([string(credentialsId: 'dockerhub-public-proxy', variable: 'DOCKERHUB_PUBLIC_PROXY')]) {
			stage('Update') {
				sh('''
					./update-remote.sh
				''')
			}
		}

		stage('Commit') {
			sh('''
				git config user.name 'Docker Library Bot'
				git config user.email 'doi+docker-library-bot@docker.com'

				for repoDir in repos/*; do
					repo="$(basename "$repoDir")"
					git add "$repoDir" || :
					git commit -m "Run update-remote.sh on $repo" || :
				done
			''')
		}

		sshagent(['docker-library-bot']) {
			stage('Push') {
				sh('''
					# try catching up since this job takes so long to run
					git checkout -- .
					git clean -dfx .
					git pull --rebase origin master || :

					# fire away!
					git push origin HEAD:master
				''')
			}
		}
	} }
}
