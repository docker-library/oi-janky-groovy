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
					includedRegions: 'library',
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

			for img in $(bashbrew list --uniq "$repo"); do
				from="$(bashbrew from --apply-constraints --uniq "$img" 2>/dev/null | cut -d' ' -f2)"
				case "$from" in
					scratch|microsoft/*) continue ;;
				esac
				if ! bashbrew list "$from" &> /dev/null; then
					echo "$img=$from"
				fi
			done
		''').tokenize()

		if (naughtyTags) {
			stage(repo) {
				str = "Naughty tags in the '${repo}' repo:\n"
				for (tagPair in naughtyTags) {
					tagPair = tagPair.tokenize('=')
					tag = tagPair[0]
					from = tagPair[1]
					str += "\n - ${tag} (FROM ${from})"
				}
				echo(str)
			}

			currentBuild.result = 'FAILURE'
		}
	} }
}
