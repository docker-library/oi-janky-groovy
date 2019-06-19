properties([
	buildDiscarder(logRotator(daysToKeepStr: '30')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H(18-23) * * *'),
	]),
])

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
					includedRegions: 'library naughty-from.sh',
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
		naughtyTags = sh(returnStdout: true, script: '''#!/usr/bin/env bash
			set -Eeuo pipefail

			oi/naughty-from.sh "$repo" 2>&1 || :
			oi/naughty-constraints.sh "$repo" 2>&1 || :
		''')

		if (naughtyTags) {
			stage(repo) {
				echo("Naughty tags in the '${repo}' repo:\n\n" + naughtyTags + "\n\n- https://github.com/docker-library/official-images/pulls?q=label%3Alibrary%2F${repo}")
			}

			currentBuild.result = 'UNSTABLE'
		}
	} }
}
