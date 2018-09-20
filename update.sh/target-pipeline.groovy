// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'update.sh/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)
def repo = env.JOB_BASE_NAME
def repoMeta = vars.repoMeta(repo)

node {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	env.BRANCH_BASE = repoMeta['branch-base']
	env.BRANCH_PUSH = repoMeta['branch-push']

	stage('Checkout') {
		sh 'mkdir -p "$BASHBREW_CACHE"'
		checkout(
			poll: false,
			changelog: false,
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
		checkout([
			$class: 'GitSCM',
			userRemoteConfigs: [[
				name: 'origin',
				url: repoMeta['url'],
				credentialsId: 'docker-library-bot',
			]],
			branches: [[name: '*/' + repoMeta['branch-push']]],
			extensions: [
				[
					$class: 'CleanCheckout',
				],
				[
					$class: 'RelativeTargetDirectory',
					relativeTargetDir: 'repo',
				],
			],
			doGenerateSubmoduleConfigurations: false,
			submoduleCfg: [],
		])
		sh '''
			cd repo

			git config user.name 'Docker Library Bot'
			git config user.email 'github+dockerlibrarybot@infosiftr.com'

			# prefill the bashbrew cache
			./generate-stackbrew-library.sh \\
				| bashbrew from --apply-constraints /dev/stdin > /dev/null
		'''

		if (repoMeta['branch-base'] != repoMeta['branch-push']) {
			sshagent(['docker-library-bot']) {
				sh '''
					git -C repo pull --rebase origin "$BRANCH_BASE"
				'''
			}
		}
	}

	def testRun = workspace + '/oi/test/run.sh'
	def testBuildNamespace = 'update.sh'

	ansiColor('xterm') { dir('repo') {
		stage('update.sh') {
			retry(3) {
				sh './update.sh'
			}
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
					version="$(gawk '
						$1 == "ENV" && $2 ~ /^'"$repoMetaEnv"'$/ {
							print $3;
							exit;
						}
					' "$dir/Dockerfile"*)"
					for otherEnvName in "${!repoMetaOtherEnvs[@]}"; do
						otherEnv="${repoMetaOtherEnvs[$otherEnvName]}"
						version+="$(gawk '
							$1 == "ENV" && $2 ~ /^'"$otherEnv"'$/ {
								print ", '"$otherEnvName"' " $3;
								exit;
							}
						' "$dir/Dockerfile"*)"
					done
					version="${version#, }"
					[ "$version" ] || continue
					versions["$version"]+=" $dir"
				done

				for version in "${!versions[@]}"; do
					dirs="${versions["$version"]}"

					git reset HEAD # just to be extra safe/careful
					for dir in $dirs; do
						git add "$dir/Dockerfile"* || true
						copiedFiles="$(awk '
							toupper($1) == "COPY" {
								for (i = 2; i < NF; i++) {
									print $i
								}
							}
						' "$dir/Dockerfile"*)"
						xargs --delimiter='\\n' -rt git -C "$dir" add <<<"$copiedFiles"
					done
					git commit -m "Update to $version" || true
				done

				# get our new commits into bashbrew
				(
					./generate-stackbrew-library.sh > "$BASHBREW_LIBRARY/$repo"

					git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:
				)
			'''
		}

		stage('Log') {
			sh 'git log -p "origin/$BRANCH_BASE...HEAD"'
		}

		def numCommits = sh(
			returnStdout: true,
			script: 'git rev-list --count "origin/$BRANCH_BASE...HEAD"',
		).trim().toInteger()
		def hasChanges = (numCommits > 0)

		if (hasChanges) {
			stage('Pull') {
				retry(3) {
					sh '#!/bin/bash -ex' + """
						bashbrew cat -f '{{ range .Entries }}{{ \$.DockerFrom . }}{{ "\\n" }}{{ end }}' '${repo}' \\
							| sort -u \\
							| grep -vE '^(scratch|microsoft/(nanoserver|windowsservercore):.*)\$' \\
							| xargs -rtn1 docker pull \\
							|| :
					"""
				}
			}

			stage('Build') {
				retry(3) {
					sh '#!/bin/bash -ex' + """
						bashbrew build '${repo}'
						bashbrew tag --namespace '${testBuildNamespace}' '${repo}'
					"""
				}
			}

			stage('Test') {
				retry(3) {
					sh '#!/bin/bash -ex' + """
						# TODO test "nanoserver" and "windowsservercore" images as well (separate Jenkins builder)
						bashbrew list --apply-constraints --uniq '${repo}' \\
							| sed 's!^!${testBuildNamespace}/!' \\
							| xargs '${testRun}'
					"""
				}
			}

			stage('Push') {
				sshagent(['docker-library-bot']) {
					sh 'git push $([ "$BRANCH_BASE" = "$BRANCH_PUSH" ] || echo --force) origin "HEAD:$BRANCH_PUSH"'
				}
			}
		} else {
			echo("No changes in ${repo}!  Skipping.")
		}
	} }
}
