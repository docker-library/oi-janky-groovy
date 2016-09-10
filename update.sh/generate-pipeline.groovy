def vars = fileLoader.fromGit(
	'update.sh/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

node {
	def workspace = sh(returnStdout: true, script: 'pwd').trim()

	env.BASHBREW_CACHE = workspace + '/bashbrew-cache'

	stage('Setup') {
		sh 'mkdir -p "$BASHBREW_CACHE"'
		checkout(
			poll: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/official-images.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[$class: 'CleanCheckout'],
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

	env.BASHBREW_LIBRARY = workspace + '/oi/library'
	def testRun = workspace + '/oi/test/run.sh'
	def testBuildNamespace = 'update.sh'

	for (repo in vars.repos) {
		def repoMeta = vars.repoMeta[repo]

		dir(repo) { stage(repo) {
			stage('Checkout') {
				checkout([
					$class: 'GitSCM',
					userRemoteConfigs: [[
						name: 'origin',
						url: repoMeta['url'],
						credentialsId: 'docker-library-bot',
					]],
					branches: [[name: '*/master']],
					extensions: [
						[$class: 'CleanCheckout'],
					],
					doGenerateSubmoduleConfigurations: false,
					submoduleCfg: [],
				])
				sh '''
					git config user.name 'Docker Library Bot'
					git config user.email 'github+dockerlibrarybot@infosiftr.com'
				'''
			}

			stage('update.sh') {
				sh '''
					# prefill the bashbrew cache
					./generate-stackbrew-library.sh \\
						| bashbrew from /dev/stdin > /dev/null

					# update this repo's contents
					./update.sh
				'''
			}

			stage('Diff') {
				sh '''
					git status
					git diff
				'''
			}

			stage('Commit') {
				def otherEnvsBash = []
				for (int x = 0; x < repoMeta['otherEnvs'].size(); ++x) {
					def key = repoMeta['otherEnvs'][x][0]
					def val = repoMeta['otherEnvs'][x][1]
					otherEnvsBash << "[${key}]='${val}'"
				}
				otherEnvsBash = otherEnvsBash.join(" ")
				sh '#!/bin/bash -ex' + """
					repo='${repo}'
					repoMetaEnv='${repoMeta['env']}'
					declare -A repoMetaOtherEnvs=( ${otherEnvsBash} )
				""" + '''
					declare -A versions=()
					for dir in $(
						./generate-stackbrew-library.sh \\
							| bashbrew cat -f '
								{{- range .Entries -}}
									{{- .Directory -}}
									{{- "\\n" -}}
								{{- end -}}
							' /dev/stdin
					); do
						version="$(awk '
							$1 == "ENV" && $2 ~ /^'"$repoMetaEnv"'$/ {
								print $3;
								exit;
							}
						' "$dir/Dockerfile"*)"
						for otherEnvName in "${!repoMetaOtherEnvs[@]}"; do
							otherEnv="${repoMetaOtherEnvs[$otherEnvName]}"
							version+="$(awk '
								$1 == "ENV" && $2 ~ /^'"$otherEnv"'$/ {
									print ", '"$otherEnvName"' " $3;
									exit;
								}
							' "$dir/Dockerfile"*)"
						done
						[ "$version" ] || continue
						versions["$version"]+=" $dir"
					done

					for version in "${!versions[@]}"; do
						dirs="${versions["$version"]}"

						git reset HEAD # just to be extra safe/careful
						for dir in $dirs; do
							git add "$dir/Dockerfile"* || true
						done
						git commit -m "Update to $version" || true
					done

					git log -p origin/master...HEAD

					# get our new commits into bashbrew
					(
						./generate-stackbrew-library.sh > "$BASHBREW_LIBRARY/$repo"

						pwd="$PWD"
						cd "$BASHBREW_CACHE/git"
						git fetch "$pwd" HEAD:
					)
				'''
			}

			stage('Test') {
				sh '#!/bin/bash -ex' + """
					if [ '0' = "\$(git rev-list --count origin/master...HEAD)" ]; then
						echo 'No changes in ${repo}!  Skipping.'
						exit
					fi
					bashbrew build --namespace '${testBuildNamespace}' '${repo}'
					bashbrew list --uniq '${repo}' \\
						| sed 's!^!${testBuildNamespace}/!' \\
						| xargs '${testRun}'
				"""
			}

			stage('Push') {
				sshagent(['docker-library-bot']) {
					sh 'git push origin HEAD:master'
				}
			}
		} }
	}
}
