properties([
	buildDiscarder(logRotator(daysToKeepStr: '30')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
	]),
])

env.OUTDATED_SCALE = 24 * 60 * 60
env.OUTDATED_SCALE_HUMAN = 'days'
env.OUTDATED_CUTOFF = 30 * 6 // how many "OUTDATED_SCALE" units before considered "outdated"

node {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

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
				[
					$class: 'PathRestriction',
					excludedRegions: '',
					includedRegions: 'library',
				],
			],
			doGenerateSubmoduleConfigurations: false,
			submoduleCfg: [],
		],
	)

	checkout(
		scm: [
			$class: 'GitSCM',
			userRemoteConfigs: [[
				url: 'https://github.com/docker-library/docs.git',
			]],
			branches: [[name: '*/master']],
			extensions: [
				[
					$class: 'CleanCheckout',
				],
				[
					$class: 'RelativeTargetDirectory',
					relativeTargetDir: 'docs',
				],
			],
			doGenerateSubmoduleConfigurations: false,
			submoduleCfg: [],
		],
	)

	repos = sh(returnStdout: true, script: '''
		bashbrew list --all --repos
	''').tokenize()

	for (repo in repos) { withEnv(['repo=' + repo]) {
		badNews = sh(returnStdout: true, script: '''#!/usr/bin/env bash
			set -Eeuo pipefail

			commit="$(git -C "$BASHBREW_LIBRARY" log -1 --format=format:%H -- "./$repo")"

			t="$(git -C "$BASHBREW_LIBRARY" log -1 --format=format:%ct "$commit" --)"
			n="$(date +%s)"

			d="$(( (n - t) / OUTDATED_SCALE ))"

			if [ "$d" -gt "$OUTDATED_CUTOFF" ]; then
				echo "$repo: $d $OUTDATED_SCALE_HUMAN since last update! ($(date -d "@$t" +%Y-%m-%d))"
				echo "- https://github.com/docker-library/official-images/commit/$commit"
				echo "- https://github.com/docker-library/official-images/pulls?q=label%3Alibrary%2F$repo"

				if [ -d "docs/$repo" ] && docsCommit="$(git -C docs log -1 --format=format:%H -- "$repo/" ":(exclude)$repo/README.md")" && [ -n "$docsCommit" ]; then
					docsT="$(git -C docs log -1 --format=format:%ct "$docsCommit" --)"
					docsD="$(date -d "@$docsT" +%Y-%m-%d)"
					echo 'docs:'
					echo "- https://github.com/docker-library/docs/commit/$docsCommit ($docsD)"
					echo "- https://github.com/docker-library/docs/tree/$docsCommit/$repo#readme"
					if [ -f "docs/$repo/deprecated.md" ]; then
						echo "- https://github.com/docker-library/docs/blob/$docsCommit/$repo/deprecated.md"
					fi
				fi
			fi
		''').trim()

		if (badNews) {
			stage(repo) {
				echo(badNews)
			}

			currentBuild.result = 'UNSTABLE'
		}
	} }
}
