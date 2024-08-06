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
		failingTags = sh(returnStdout: true, script: '''#!/usr/bin/env bash
			set -Eeuo pipefail

			arches="$(
				bashbrew cat --format '
					{{- range .Entries -}}
						{{- join "\\n" .Architectures -}}
						{{- "\\n" -}}
					{{- end -}}
				' "$repo" | sort -u
			)"

			failingTags=()
			declare -A failingTagArches=()
			for arch in $arches; do
				tags="$(wget --timeout=5 -qO- "https://doi-janky.infosiftr.net/job/multiarch/job/$arch/job/$repo/lastSuccessfulBuild/artifact/build-info/failed.txt" || :)"
				[ -n "$tags" ] || continue
				IFS=$'\\n'; tags=( $tags ); unset IFS

				for tag in "${tags[@]}"; do
					if [ -z "${failingTagArches[$tag]:-}" ]; then
						failingTags+=( "$tag" )
						failingTagArches[$tag]="$arch"
					else
						failingTagArches[$tag]+=",$arch"
					fi
				done
			done

			if [ "${#failingTags[@]}" -gt 0 ]; then
				for tag in "${failingTags[@]}"; do
					echo "$tag=${failingTagArches[$tag]}"
				done
			fi
		''').tokenize('\n')

		if (failingTags) {
			stage(repo) {
				str = "Failing tags in the '${repo}' repo:\n"
				for (failingTag in failingTags) {
					tagPair = failingTag.tokenize('=')
					tag = tagPair[0]
					arches = tagPair[1].tokenize(',')
					str += "\n - ${tag} [${arches.join(', ')}]"
				}
				echo(str)
			}

			currentBuild.result = 'UNSTABLE'
		}
	} }
}
