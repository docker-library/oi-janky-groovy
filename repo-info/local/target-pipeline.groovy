def repo = env.JOB_BASE_NAME

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

	def tags = []
	stage('Gather') {
		def tagGroups = sh(
			returnStdout: true,
			script: """
				repo='${repo}'
			""" + '''
				export BASHBREW_LIBRARY="$PWD/oi/library"
				bashbrew cat -f '
					{{- range $.Entries -}}
						{{- if not ($.SkipConstraints .) -}}
							{{- join " " .Tags -}}
							{{- "\\n" -}}
						{{- end -}}
					{{- end -}}
				' "$repo"
			''',
		).trim().tokenize('\n')
		for (tagGroup in tagGroups) {
			tags << tagGroup.tokenize(' ')
		}
	}

	wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) { dir('ri') {
		stage('Prepare') {
			sh '''
				docker pull $(awk '$1 == "FROM" { print $2; exit }' Dockerfile.local)
				sed -i 's/ --pull / /g' scan-local.sh
				! grep -q -- '--pull' scan-local.sh
			'''
		}

		stage('Scan') {
			sh """
				rm -rf 'repos/${repo}/local'
				mkdir -p 'repos/${repo}/local'
			"""

			for (tagGroup in tags) {
				def firstTagName = tagGroup[0]
				def firstTag = repo + ':' + firstTagName
				def firstTarget = "repos/${repo}/local/${firstTagName}.md"
				stage(firstTag) {
					def shells = [
						"""
							docker pull '${firstTag}'
							./scan-local.sh '${firstTag}' > '${firstTarget}'
						""",
					]
					for (int i = 1; i < tagGroup.size(); ++i) {
						def nextTagName = tagGroup[i]
						def nextTarget = "repos/${repo}/local/${nextTagName}.md"
						shells << "cp '${firstTarget}' '${nextTarget}'"
					}
					sh(shells.join('\n'))
				}
			}
		}

		stage('Commit') {
			sh """
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'

				git add 'repos/${repo}/local' || :
				git commit -m 'Run scan-local.sh on ${repo}:...' || :
			"""
		}

		stage('Log') {
			sh 'git log -p origin/master...HEAD'
		}

		def numCommits = sh(
			returnStdout: true,
			script: 'git rev-list --count origin/master...HEAD',
		).trim().toInteger()
		def hasChanges = (numCommits > 0)

		stage('Push') {
			if (hasChanges) {
				sshagent(['docker-library-bot']) {
					sh '''
						# try catching up since this job takes so long to run sometimes
						git checkout -- .
						git clean -dfx .
						git pull --rebase origin master || :

						# fire away!
						git push origin HEAD:master
					'''
				}
			} else {
				echo("No changes in ${repo}!  Skipping.")
			}
		}
	} }
}
