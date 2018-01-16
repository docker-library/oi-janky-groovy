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
				naughtyArches=()

				for BASHBREW_ARCH in $(bashbrew cat --format '{{ join " " .TagEntry.Architectures }}' "$img"); do
					export BASHBREW_ARCH

					from="$(bashbrew cat --format '{{ $.DockerFrom .TagEntry }}' "$img")"
					case "$from" in
						# a few explicitly permissible exceptions to Santa's naughty list
						scratch \
						| microsoft/windowsservercore \
						| microsoft/windowsservercore:* \
						| microsoft/nanoserver \
						| microsoft/nanoserver:* \
						) continue ;;
					esac

					if ! listOutput="$(bashbrew list --apply-constraints "$from")" || [ -z "$listOutput" ]; then
						naughtyArches+=( "$BASHBREW_ARCH" )
					fi
				done

				# TODO this block uses the leaky "from" from above, which might actually change per-arch, so we really need to track arches-per-from for each of these repo:tags (but it's not a big deal, hence TODO rather than a code fix)
				if [ "${#naughtyArches[@]}" -ne 0 ]; then
					IFS=','
					echo "$img=$from=${naughtyArches[*]}"
					unset IFS
				fi
			done
		''').tokenize()

		if (naughtyTags) {
			stage(repo) {
				str = "Naughty tags in the '${repo}' repo:\n"
				for (naughtyTag in naughtyTags) {
					tagPair = naughtyTag.tokenize('=')
					tag = tagPair[0]
					from = tagPair[1]
					arches = tagPair[2].tokenize(',')
					str += "\n - ${tag} (FROM ${from}) [${arches.join(', ')}]"
				}
				echo(str)
			}

			currentBuild.result = 'FAILURE'
		}
	} }
}
