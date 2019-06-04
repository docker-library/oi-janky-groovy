node {
	stage('Checkout o-i') {
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
		sh '''
			git -C oi config user.name 'Docker Library Bot'
			git -C oi config user.email 'github+dockerlibrarybot@infosiftr.com'
		'''
	}

	latestTimestamp = '0'
	changes = []

	for (repo in ['elasticsearch', 'logstash', 'kibana']) {
		stage('Checkout ' + repo) {
			checkout(
				poll: false,
				changelog: false,
				scm: [
					$class: 'GitSCM',
					userRemoteConfigs: [[
						url: 'https://github.com/docker-library/' + repo + '.git',
					]],
					branches: [[name: '*/master']],
					extensions: [
						[
							$class: 'CleanCheckout',
						],
						[
							$class: 'RelativeTargetDirectory',
							relativeTargetDir: repo,
						],
					],
					doGenerateSubmoduleConfigurations: false,
					submoduleCfg: [],
				],
			)
		}

		withEnv([ 'repo=' + repo ]) {
			stage('Generate ' + repo) {
				repoChanges = sh(returnStdout: true, script: '''#!/usr/bin/env bash
					set -Eeuo pipefail -x

					prevDate="$(git -C oi log -1 --format='format:%at' "library/$repo")"
					(( prevDate++ )) || :
					prevDate="$(date --date "@$prevDate" --rfc-2822)"

					url="https://github.com/docker-library/$repo"
					changes="$(git -C "$repo" log --after="$prevDate" --format="- $url/commit/%h: %s" || :)"
					# look for "#NNN" so we can explicitly link to any PRs too
					changes="$(sed -r "s!#([0-9]+)!$url/pull/\\\\1!g" <<<"$changes")"

					if [ -n "$changes" ]; then
						echo "$repo:"
						echo "$changes"
					fi
				''').trim()
				repoTimestamp = sh(returnStdout: true, script: '''
					git -C "$repo" log -1 --format='format:%at'
				''').trim()

				sh 'cd "$repo" && ./generate-stackbrew-library.sh > "../oi/library/$repo"'

				if (repoTimestamp > latestTimestamp) {
					latestTimestamp = repoTimestamp
				}
				if (repoChanges) {
					changes += [ repoChanges ]
				}
			}
		}
	}

	withEnv([ 'timestamp=' + latestTimestamp, 'changes=' + changes.join('\n\n') ]) {
		stage('Stage PR') {
			sshagent(['docker-library-bot']) {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail -x

					pushed=

					if [ "$timestamp" -gt 0 ] && [ -n "$changes" ]; then
						commitArgs=( -m "Update ELK images" -m "$changes" )

						date="$(date --rfc-2822 --date "@$timestamp")"
						export GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date"
						if git -C oi add library/ && git -C oi commit "${commitArgs[@]}"; then
							git -C oi push -f git@github.com:docker-library-bot/official-images.git "HEAD:refs/heads/elk"
							pushed=1
						fi
					fi

					if [ -z "$pushed" ]; then
						git -C oi push --delete git@github.com:docker-library-bot/official-images.git refs/heads/elk
					fi
				'''
			}
		}
	}
}
