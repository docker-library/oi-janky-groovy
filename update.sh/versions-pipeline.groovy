// properties are set via "generate-pipeline.groovy" (jobDsl)

// This file is intended for use by repositories that have adopted the newer "versions.json" templating (https://github.com/docker-library/php/pull/1052, etc).

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
	env.repo = repo
	env.BRANCH_BASE = repoMeta['branch-base']
	env.BRANCH_PUSH = repoMeta['branch-push']

	env.BASHBREW_CACHE = workspace + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = workspace + '/oi/library'
	env.BASHBREW_NAMESPACE = 'update.sh'
	dir(env.BASHBREW_CACHE) { deleteDir() }
	sh 'mkdir -p "$BASHBREW_CACHE"'

	env.TEST_RUN_SH = workspace + '/oi/test/run.sh'

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
			branches: [
				[name: '*/' + repoMeta['branch-push']],
				[name: '*/' + repoMeta['branch-base']],
			],
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
		'''

		if (repoMeta['branch-base'] != repoMeta['branch-push']) {
			sshagent(['docker-library-bot']) {
				sh '''
					git -C repo pull --rebase origin "$BRANCH_BASE"
				'''
			}
		}

		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail -x

			docker build --pull --tag oisupport/update.sh 'https://github.com/docker-library/oi-janky-groovy.git#:update.sh'

			# prefill the bashbrew cache
			cd repo
			user="$(id -u):$(id -g)"
			docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD,ro" --workdir "$PWD" oisupport/update.sh \\
				./generate-stackbrew-library.sh \\
				| bashbrew from --apply-constraints /dev/stdin > /dev/null
		'''
	}

	ansiColor('xterm') { dir('repo') {
		def initialCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()

		def versions = sh(
			script: '''
				jq -r 'keys[]' versions.json
			''',
			returnStdout: true,
		).tokenize()

		for (def version in versions) { withEnv(['version=' + version]) {
			def commit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()

			try {
				stage('Update ' + version) {
					sh '''#!/usr/bin/env bash
						set -Eeuo pipefail -x

						# gather a list of this version's components first so we can diff it later and generate a useful commit message ("Update 1.2 to 1.2.4", "Update 3.4 to openssl 1.1.1g", etc)
						version_components() {
							jq -r '
								.[env.version] // {} | [
									.version // empty,
									(
										to_entries[]
										| select(.value | type == "object" and has("version"))
										| .key + " " + .value.version
									)
								] | join("\n")
							' versions.json
						}
						componentsBefore="$(version_components)"

						user="$(id -u):$(id -g)"
						docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD" --workdir "$PWD" oisupport/update.sh \\
							./update.sh "$version"

						componentsAfter="$(version_components)"
						componentsChanged="$(comm -13 <(echo "$componentsBefore") <(echo "$componentsAfter"))"

						# Example generated commit messages:
						#   Update 3.7
						#   Update 3.7 to 3.7.14
						#   Update 3.7 to 3.7.14, openssl 1.1.1g
						#   Update 3.7 to openssl 1.1.1g
						# etc.
						commitMessage="Update $version"
						if [ -n "$componentsChanged" ]; then
							components="$(jq <<<"$componentsChanged" -rRs 'rtrimstr("\n") | split("\n") | join(", ")')"
							commitMessage+=" to $components"
						fi
						git add -A . || :
						git commit -m "$commitMessage" || :
					'''
				}

				def newCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
				def didChange = (newCommit != commit)

				// Jenkins is silly about stages, so we want to create all the same ones every time as often as we can (even if they end up being fully empty).

				stage('Diff ' + version) { if (didChange) {
					sh 'git log -1 --stat -p'
				} }

				stage('Build ' + version) { if (didChange) {
					timeout(time: 6, unit: 'HOURS') {
						retry(3) {
							sh '''
								# force our new commits into bashbrew
								user="$(id -u):$(id -g)"
								docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD,ro" --workdir "$PWD" oisupport/update.sh \\
									./generate-stackbrew-library.sh "$version" \\
									> "$BASHBREW_LIBRARY/$repo"
								git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:

								bashbrew cat -f '{{ range .Entries }}{{ $.DockerFroms . | join "\\n" }}{{ "\\n" }}{{ end }}' "$repo" \\
									| sort -u \\
									| grep -vE '^(scratch|mcr.microsoft.com/windows/(nanoserver|servercore):.*|'"$repo"':.*)$' \\
									| xargs -rtn1 docker pull \\
									|| :

								images="$(bashbrew --namespace '' list --build-order --uniq "$repo")"
								for image in $images; do
									bashbrew build "$image"
									bashbrew tag --target-namespace '' "$image" # in case we have interdependent images
								done
							'''
						}
					}
				} }

				stage('Test ' + version) { if (didChange) {
					timeout(time: 1, unit: 'HOURS') {
						retry(3) {
							sh 'bashbrew list --apply-constraints --uniq "$repo" | xargs -rt "$TEST_RUN_SH"'
						}
					}
				} }
			}
			catch (err) {
				// if there's an error updating this version, make it like we were never here (and set the build as "unstable")
				withEnv(['commit=' + commit]) {
					sh '''
						git reset --hard "$commit"
						git clean -dffx
					'''
				}
				echo "ERROR while updating ${version}: ${err}"
				// "catchError" is the only way to set "stageResult" :(
				catchError(message: "ERROR while updating ${version}: ${err}", buildResult: 'UNSTABLE', stageResult: 'FAILURE') { error() }
			}
		} }

		// smoke test to ensure the final result is fully valid (and we didn't cause a glitch in "generate-stackbrew-library.sh" like causing two things to share the "buildpack-deps:stable" alias, for example)
		stage('Validate') {
			sh '''
				user="$(id -u):$(id -g)"
				docker run --init --rm --user "$user" --mount "type=bind,src=$PWD,dst=$PWD,ro" --workdir "$PWD" oisupport/update.sh \\
					./generate-stackbrew-library.sh \\
					> "$BASHBREW_LIBRARY/$repo"
				git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:
				bashbrew from --uniq "$repo"

				../oi/naughty-from.sh "$repo"
				../oi/naughty-constraints.sh "$repo"
			'''
		}

		def newCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
		stage('Push') { if (newCommit != initialCommit) {
			sshagent(['docker-library-bot']) {
				sh 'git push $([ "$BRANCH_BASE" = "$BRANCH_PUSH" ] || echo --force) origin "HEAD:$BRANCH_PUSH"'
			}
		} }
	} }

	stage('Stage PR') {
		sshagent(['docker-library-bot']) {
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

				oi/naughty-from.sh "$repo"
				oi/naughty-constraints.sh "$repo"

				date="$(git -C repo log -1 --format='format:%aD')"
				export GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date"
				if [ "$BRANCH_BASE" = "$BRANCH_PUSH" ] && git -C oi add "$BASHBREW_LIBRARY/$repo" && git -C oi commit "${commitArgs[@]}"; then
					if diff "$BASHBREW_LIBRARY/$repo" <(wget -qO- "$oiForkUrl/raw/$repo/library/$repo") &> /dev/null; then
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
