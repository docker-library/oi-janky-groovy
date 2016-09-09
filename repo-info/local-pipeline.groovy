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

	def repos = []
	def images = [:]
	stage('Gather') {
		repoTags = sh(
			returnStdout: true,
			script: '''
				export BASHBREW_LIBRARY="$PWD/oi/library"
				bashbrew cat -f '
					{{- range $.Entries -}}
						{{- if not ($.SkipConstraints .) -}}
							{{- join " " ($.Tags "" false .) -}}
							{{- "\\n" -}}
						{{- end -}}
					{{- end -}}
				' --all
			''',
		).trim().tokenize('\n')
		for (repoTag in repoTags) {
			repoTag = repoTag.tokenize(' ')
			repo = repoTag[0].tokenize(':')[0]
			if (!images[repo]) {
				repos << repo
				images[repo] = []
			}
			images[repo] << repoTag
		}
	}

	stage('Prepare') {
		sh('''
			docker pull $(awk '$1 == "FROM" { print $2; exit }' ri/Dockerfile.local)
			sed -i 's/ --pull / /g' ri/scan-local.sh
			! grep -q -- '--pull' ri/scan-local.sh
		''')
	}

	stage('Scan') {
		int concurrency = 4 // TODO tweak this to be reasonable
		def repoBuckets = []
		for (int i = 0; i < concurrency; ++i) {
			repoBuckets << []
		}
		for (int i = 0; i < repos.size(); ++i) {
			repoBuckets[i % concurrency] << repos[i]
		}
		def parallelBuilds = [:]
		for (int i = 0; i < concurrency; ++i) {
			def bucketRepos = repoBuckets[i]
			def title = "bucket-${i}"
			echo("${title}: ${bucketRepos.join(', ')}")
			parallelBuilds[title] = {
				for (repo in bucketRepos) {
					stage(repo) {
						def shells = [
							'cd ri',
							"rm -rf 'repos/${repo}/local'",
							"mkdir -p 'repos/${repo}/local'",
						]
						for (repoTag in images[repo]) {
							def firstTag = repoTag[0]
							def firstTagName = firstTag.tokenize(':')[1]
							def firstTarget = "repos/${repo}/local/${firstTagName}.md"
							shells << """
								docker pull '${firstTag}'
								./scan-local.sh '${firstTag}' > '${firstTarget}'
							"""
							for (i = 1; i < repoTag.size(); ++i) {
								def nextTag = repoTag[i]
								def nextTagName = nextTag.tokenize(':')[1]
								def nextTarget = "repos/${repo}/local/${nextTagName}.md"
								shells << "cp '${firstTarget}' '${nextTarget}'"
							}
						}
						sh(shells.join('\n'))
					}
				}
			}
		}
		parallel parallelBuilds
	}

	stage('Commit') {
		shells = [
			'cd ri',
			'''
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'
			''',
		]
		for (repo in repos) {
			shells << """
				git add 'repos/${repo}/local' || :
				git commit -m 'Run scan-local.sh on ${repo}:...' || :
			"""
		}
		sh(shells.join('\n'))
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
