// properties are set via "generate-pipeline.groovy" (jobDsl)

// setup environment variables, etc.
env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc

node {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	env.BASHBREW_ARCH = env.ACT_ON_ARCH

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

	repos = sh(returnStdout: true, script: '''
		bashbrew cat --all --format '
			{{- range .Entries -}}
				{{- if .HasArchitecture arch -}}
					{{- $.RepoName -}}{{- "\\n" -}}
				{{- end -}}
			{{- end -}}
		' | sort -u
	''').tokenize()
	for (repo in repos) { stage(repo) { withEnv(['REPO=' + repo]) {
		if (0 != sh(returnStatus: true, script: '''#!/usr/bin/env bash
			set -Eeuo pipefail
			set -x
			commit="$(wget -qO- "https://doi-janky.infosiftr.net/job/multiarch/job/$ACT_ON_ARCH/job/$REPO/lastSuccessfulBuild/artifact/build-info/commit.txt")"
			[ -n "$commit" ]
			touchingCommits="$(git -C "$BASHBREW_LIBRARY" log --oneline "$commit...HEAD" -- "library/$REPO")"
			[ -z "$touchingCommits" ]
		''')) {
			echo "build job: ${repo}, wait: false"
		}
	} } }
}
