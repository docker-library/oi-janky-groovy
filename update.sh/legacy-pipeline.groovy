// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'update.sh/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)
def repo = env.JOB_BASE_NAME
def repoMeta = vars.repoMeta(repo)

node {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	dir(env.BASHBREW_CACHE) { deleteDir() }

	env.BRANCH_BASE = repoMeta['branch-base']
	env.BRANCH_PUSH = repoMeta['branch-push']

	stage('Checkout') {
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
		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail -x

			git -C oi config user.name 'Docker Library Bot'
			git -C oi config user.email 'doi+docker-library-bot@docker.com'
			git -C repo config user.name 'Docker Library Bot'
			git -C repo config user.email 'doi+docker-library-bot@docker.com'

			# https://github.com/moby/moby/issues/30973 🤦
			# docker build --pull --tag oisupport/update.sh 'https://github.com/docker-library/oi-janky-groovy.git#:update.sh'
			tempDir="$(mktemp -d -t docker-build-janky-XXXXXXXXXX)"
			trap 'rm -rf "$tempDir"' EXIT
			git clone --depth 1 https://github.com/docker-library/oi-janky-groovy.git "$tempDir"
			docker build --pull --tag oisupport/update.sh "$tempDir/update.sh"

			# prefill the bashbrew cache
			cd repo
			user="$(id -u):$(id -g)"
			docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD,ro" --workdir "$PWD" oisupport/update.sh \\
				./generate-stackbrew-library.sh \\
				| bashbrew fetch --arch-filter /dev/stdin
		'''

		if (repoMeta['branch-base'] != repoMeta['branch-push']) {
			sshagent(credentials: ['docker-library-bot'], ignoreMissing: true) {
				sh '''
					git -C repo pull --rebase origin "$BRANCH_BASE"
				'''
			}
		}
	}

	def testRun = workspace + '/oi/test/run.sh'
	def testBuildNamespace = 'update.sh'

	// https://github.com/docker-library/official-images/pull/14212
	def buildEnvsJson = sh(returnStdout: true, script: '''#!/usr/bin/env bash
		set -Eeuo pipefail -x

		oi/.bin/bashbrew-buildkit-env-setup.sh \\
			| tee /dev/stderr \\
			| jq 'to_entries | map(.key + "=" + .value)'
	''').trim()
	def buildEnvs = []
	if (buildEnvsJson) {
		buildEnvs += readJSON(text: buildEnvsJson)
	}

	ansiColor('xterm') { withEnv(buildEnvs) { dir('repo') {
		env.UPDATE_SCRIPT = repoMeta['update-script']
		stage('update.sh') {
			retry(3) {
				sh '''
					user="$(id -u):$(id -g)"
					docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD" --workdir "$PWD" oisupport/update.sh \\
						bash -Eeuo pipefail -xc "$UPDATE_SCRIPT"
				'''
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
				repoMetaFrom='${repoMeta['from'] ?: ''}'
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
				dfdirs="$(
					user="$(id -u):$(id -g)"
					docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD,ro" --workdir "$PWD" oisupport/update.sh \\
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

				declare -A dirVersions=()
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
					if [ -z "$version" ] && parentDir="$(dirname "$dir")" && [ -n "${dirVersions[$parentDir]:-}" ]; then
						# if we don't have a version, but our parent directory does, let's assume it's something like "rabbitmq:management" (where the version number is technically the version number of the parent directory)
						version="${dirVersions[$parentDir]}"
					fi
					[ -n "$version" ] || continue
					dirVersions["$dir"]="$version"
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
									if ($i ~ /^--from=/) {
										next
									}
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
					user="$(id -u):$(id -g)"
					docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD,ro" --workdir "$PWD" oisupport/update.sh \\
						./generate-stackbrew-library.sh \\
						> "$BASHBREW_LIBRARY/$repo"

					gitCache="$(bashbrew cat --format '{{ gitCache }}' "$repo")"
					git -C "$gitCache" fetch "$PWD" HEAD:
				)
			'''
		}

		stage('Log') {
			sh 'git log -p --irreversible-delete "origin/$BRANCH_BASE...HEAD"'
		}

		def numCommits = sh(
			returnStdout: true,
			script: 'git rev-list --count "origin/$BRANCH_BASE...HEAD"',
		).trim().toInteger()
		def hasChanges = (numCommits > 0)

		if (hasChanges) {
			stage('Pull') {
				timeout(time: 1, unit: 'HOURS') {
					retry(3) {
						sh '#!/bin/bash -ex' + """
							bashbrew cat -f '{{ range .Entries }}{{ \$.DockerFroms . | join "\\n" }}{{ "\\n" }}{{ end }}' '${repo}' \\
								| sort -u \\
								| grep -vE '^(scratch|mcr.microsoft.com/windows/(nanoserver|servercore):.*|microsoft/(nanoserver|windowsservercore):.*|${repo}:.*)\$' \\
								| xargs -rtn1 docker pull \\
								|| :
						"""
					}
				}
			}

			stage('Build') {
				timeout(time: 6, unit: 'HOURS') {
					retry(3) {
						sh '#!/bin/bash -ex' + """
							bashbrew build '${repo}'
							bashbrew tag --target-namespace '${testBuildNamespace}' '${repo}'
						"""
					}
				}
			}

			stage('Test') {
				timeout(time: 1, unit: 'HOURS') {
					retry(3) {
						sh '#!/bin/bash -ex' + """
							# TODO test "nanoserver" and "windowsservercore" images as well (separate Jenkins builder)
							bashbrew --namespace '${testBuildNamespace}' list --apply-constraints --uniq '${repo}' \\
								| xargs '${testRun}'
						"""
					}
				}
			}

			stage('Push') {
				sshagent(credentials: ['docker-library-bot'], ignoreMissing: true) {
					sh 'git push $([ "$BRANCH_BASE" = "$BRANCH_PUSH" ] || echo --force) origin "HEAD:$BRANCH_PUSH"'
				}
			}
		} else {
			echo("No changes in ${repo}!  Skipping.")
		}
	} } }

	if (repoMeta['bot-branch']) {
		stage('Stage PR') {
			sshagent(credentials: ['docker-library-bot'], ignoreMissing: true) {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail
					set -x
				''' + """
					repo='${repo}'
					url='${repoMeta['url'].replaceFirst(':', '/').replaceFirst('git@', 'https://').replaceFirst('[.]git$', '')}'
					oiFork='${repoMeta['oi-fork']}'
					oiForkUrl='${repoMeta['oi-fork'].replaceFirst(':', '/').replaceFirst('git@', 'https://').replaceFirst('[.]git$', '')}'
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

					(
						cd repo
						user="$(id -u):$(id -g)"
						docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD,ro" --workdir "$PWD" oisupport/update.sh \\
							./generate-stackbrew-library.sh \\
							> "$BASHBREW_LIBRARY/$repo"
					)

					naughty="$(
						oi/naughty-from.sh "$repo"
						oi/naughty-constraints.sh "$repo"
					)"
					[ -z "$naughty" ]

					date="$(git -C repo log -1 --format='format:%aD')"
					export GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date"
					if [ "$BRANCH_BASE" = "$BRANCH_PUSH" ] && git -C oi add "$BASHBREW_LIBRARY/$repo" && git -C oi commit "${commitArgs[@]}"; then
						if diff "$BASHBREW_LIBRARY/$repo" <(wget --timeout=5 -qO- "$oiForkUrl/raw/$repo/library/$repo") &> /dev/null; then
							# if this exact file content is already pushed to a bot branch, don't force push it again
							exit
						fi
						git -C oi push -f "$oiFork" "HEAD:refs/heads/$repo"
					else
						git -C oi push "$oiFork" --delete "refs/heads/$repo"
					fi
				'''
			}
		}
	}
}
