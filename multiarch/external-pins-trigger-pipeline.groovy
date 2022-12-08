properties([
	buildDiscarder(logRotator(daysToKeepStr: '4')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('@hourly'),
	]),
])

node {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
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
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	dir('oi') {
		pins = sh(returnStdout: true, script: '.external-pins/list.sh').tokenize()

		for (pin in pins) { stage(pin) { withEnv(['PIN=' + pin]) {
			children = sh(returnStdout: true, script: '''#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x

				bashbrew children --depth 1 "$PIN" | xargs -rt bashbrew cat --format '
						{{- range $e := .Entries -}}
							{{- range $a := $e.Architectures -}}
								{{- $a -}}
								{{- "/" -}}
								{{- $.RepoName -}}
								{{- "\n" -}}
							{{- end -}}
						{{- end -}}
					' | sort -u
			''').tokenize()

			for (child in children) { withEnv(['CHILD=' + child]) {
				if (0 != sh(returnStatus: true, script: '''#!/usr/bin/env bash
					set -Eeuo pipefail
					set -x
					arch="${CHILD%/*}"
					repo="${CHILD#*/}"
					commit="$(wget -qO- "https://doi-janky.infosiftr.net/job/multiarch/job/$arch/job/$repo/lastSuccessfulBuild/artifact/build-info/commit.txt")"
					[ -n "$commit" ]
					file="$(.external-pins/file.sh "$PIN")"
					[ -s "$file" ]
					dir="$(dirname "$file")"
					file="$(basename "$file")"
					touchingCommits="$(git -C "$dir" log --oneline "$commit...HEAD" -- "$file")"
					[ -z "$touchingCommits" ]
				''')) {
					catchError(stageResult: 'FAILURE') {
						build(
							job: child,
							quietPeriod: 15 * 60, // 15 minutes
							wait: false,
						)

						// also mark the build as unstable so it's obvious which trigger jobs actually triggered builds
						currentBuild.result = 'UNSTABLE'
					}
				}
			} }
		} } }
	}
}
