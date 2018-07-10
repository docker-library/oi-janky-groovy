properties([
	buildDiscarder(logRotator(daysToKeepStr: '30')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
	]),
])

repos = [
	'debian',
	'ubuntu',
]
env.OUTDATED_SCALE = 24 * 60 * 60
env.OUTDATED_SCALE_HUMAN = 'days'
env.OUTDATED_CUTOFF = 30 // how many "OUTDATED_SCALE" units before considered "outdated"

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

	for (repo in repos) { withEnv(['repo=' + repo]) {
		badNews = sh(returnStdout: true, script: '''#!/usr/bin/env bash
			set -Eeuo pipefail

			t="$(git -C "$BASHBREW_LIBRARY" log -1 --format=format:%ct "./$repo")"
			n="$(date +%s)"

			d="$(( (n - t) / OUTDATED_SCALE ))"

			if [ "$d" -gt "$OUTDATED_CUTOFF" ]; then
				echo "$repo: $d $OUTDATED_SCALE_HUMAN since last update!"
			fi
		''').trim()

		if (badNews) {
			stage(repo) {
				echo(badNews)
			}

			currentBuild.result = 'FAILURE'
		}
	} }
}
