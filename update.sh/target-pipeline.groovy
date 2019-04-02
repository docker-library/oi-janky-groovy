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
			git -C oi config user.name 'Docker Library Bot'
			git -C oi config user.email 'github+dockerlibrarybot@infosiftr.com'
			git -C repo config user.name 'Docker Library Bot'
			git -C repo config user.email 'github+dockerlibrarybot@infosiftr.com'

			# prefill the bashbrew cache
			cd repo
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
				sh repoMeta['update-script']
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
				repoMetaFrom='${repoMeta['from']}'
			""" + '''
				declare -A versions=()

				# commit all changes so "generate-stackbrew-library.sh" includes everything for sure
				beforeTempCommit="$(git rev-parse HEAD)"
				if ! {
					git add -A . \
						&& git commit -m 'Temporary commit (just for "generate-stackbrew-library.sh")'
				}; then
					# nothing to do, break early
					exit 0
				fi
					# nothing to do, break early
					exit 0
				fi
				dfdirs="$(
					./generate-stackbrew-library.sh \\
						| bashbrew cat -f '
							{{- range .Entries -}}
								{{- .File -}} = {{- .Directory -}}
								{{- "\\n" -}}
							{{- end -}}
						' /dev/stdin
				)"
				# then revert the temp commit so we can make real commits
				git reset --mixed "$beforeTempCommit"

				for dfdir in $dfdirs; do
					df="${dfdir%%=*}" # "Dockerfile", etc
					dir="${dfdir#$df=}" # "2.4/alpine", etc
					[ "$df" = 'Dockerfile' ] && dfs=( "$dir/$df"* ) || dfs=( "$dir/$df" )
					version="$(gawk '
						$1 == "ENV" && $2 ~ /^'"$repoMetaEnv"'$/ {
							print $3;
							exit;
						}
					' "${dfs[@]}")"
					if [ -z "$version" ] && [ -n "$repoMetaFrom" ]; then
						version="$(gawk -F '[ :@]+' -v from="$repoMetaFrom" '
							$1 == "FROM" && $2 == from {
								print $3;
								exit;
							}
						' "${dfs[@]}")"
					fi
					for otherEnvName in "${!repoMetaOtherEnvs[@]}"; do
						otherEnv="${repoMetaOtherEnvs[$otherEnvName]}"
						version+="$(gawk '
							$1 == "ENV" && $2 ~ /^'"$otherEnv"'$/ {
								print ", '"$otherEnvName"' " $3;
								exit;
							}
						' "${dfs[@]}")"
					done
					version="${version#, }"
					[ -n "$version" ] || continue
					versions["$version"]+=" $dfdir"
				done

				for version in "${!versions[@]}"; do
					dirs="${versions["$version"]}"

					git reset HEAD # just to be extra safe/careful
					for dfdir in $dirs; do
						df="${dfdir%%=*}" # "Dockerfile", etc
						dir="${dfdir#$df=}" # "2.4/alpine", etc
						[ "$df" = 'Dockerfile' ] && dfs=( "$dir/$df"* ) || dfs=( "$dir/$df" )
						[ "${#dfs[@]}" -gt 0 ] || continue
						git add "${dfs[@]}" || true
						copiedFiles="$(awk '
							toupper($1) == "COPY" {
								for (i = 2; i < NF; i++) {
									print $i
								}
							}
						' "${dfs[@]}")"
						[ -n "$copiedFiles" ] || continue
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
							| grep -vE '^(scratch|mcr.microsoft.com/windows/(nanoserver|servercore)|microsoft/(nanoserver|windowsservercore):.*|${repo}:.*)\$' \\
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

	stage('Stage PR') {
		sshagent(['docker-library-bot']) {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x
			''' + """
				repo='${repo}'
				url='${repoMeta['url'].replaceFirst(':', '/').replaceFirst('git@', 'https://').replaceFirst('[.]git$', '')}'
			""" + '''
				git -C oi reset HEAD
				git -C oi clean -dfx
				git -C oi checkout -- .

				prevDate="$(git -C oi log -1 --format='format:%at' "$BASHBREW_LIBRARY/$repo")"
				(( prevDate++ )) || :
				prevDate="$(date --date "@$prevDate" --rfc-2822)"
				changesFormat='- %h: %s'
				prSed=''
				case "$url" in
					*github.com*)
						changesFormat="- $url/commit/%h: %s"
						# look for "#NNN" so we can explicitly link to any PRs too
						prSed="s!#([0-9]+)!$url/pull/\\\\1!g"
						;;
				esac
				changes="$(git -C repo log --after="$prevDate" --format="$changesFormat" || :)"
				if [ -n "$prSed" ]; then
					changes="$(sed -r "$prSed" <<<"$changes")"
				fi

				commitArgs=( -m "Update $repo" )
				if [ -n "$changes" ]; then
					# might be something like just "Architectures:" changes for which there's no commits
					commitArgs+=( -m 'Changes:' -m "$changes" )
				fi

				( cd repo && ./generate-stackbrew-library.sh > "$BASHBREW_LIBRARY/$repo" )

				date="$(git -C repo log -1 --format='format:%aD')"
				export GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date"
				if [ "$BRANCH_BASE" = "$BRANCH_PUSH" ] && oi/naughty-from.sh "$repo" && git -C oi add "$BASHBREW_LIBRARY/$repo" && git -C oi commit "${commitArgs[@]}"; then
					if diff "$BASHBREW_LIBRARY/$repo" <(wget -qO- "https://github.com/docker-library-bot/official-images/raw/$repo/library/$repo") &> /dev/null; then
						# if this exact file content is already pushed to a bot branch, don't force push it again
						exit
					fi
					git -C oi push -f git@github.com:docker-library-bot/official-images.git "HEAD:refs/heads/$repo"
				else
					git -C oi push git@github.com:docker-library-bot/official-images.git --delete "refs/heads/$repo"
				fi
			'''
		}
	}
}
