// properties are set via "generate-pipeline.groovy" (jobDsl)

// This file is intended for updating .external-pins (https://github.com/docker-library/official-images/tree/master/.external-pins).

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'update.sh/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)
def repo = env.JOB_BASE_NAME
def repoMeta = vars.repoMeta(repo)

node {
	env.repo = repo

	stage('Checkout') {
		checkout(
			poll: false,
			changelog: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [
					[
						name: 'origin',
						url: 'https://github.com/docker-library/official-images.git',
					],
					[
						name: 'fork',
						url: repoMeta['oi-fork'],
						credentialsId: 'docker-library-bot',
					],
				],
				branches: [[name: 'origin/master']],
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
			git -C oi config user.email 'doi+docker-library-bot@docker.com'
		'''
	}

	ansiColor('xterm') { dir('oi') {
		env.headCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()

		def orgs = sh(
			script: '''
				.external-pins/list.sh | cut -d/ -f1 | sort -u
			''',
			returnStdout: true,
		).tokenize()

		for (def org in orgs) { withEnv(['org=' + org, 'branch=' + repo + '-' + org]) {
			sshagent(credentials: ['docker-library-bot'], ignoreMissing: true) {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail -x

					git reset --hard "$headCommit"

					# if there's an existing branch for updating external-pins, we should start there to avoid rebasing our branch too aggressively
					if git fetch fork "refs/heads/$branch:" && ! git merge-base --is-ancestor FETCH_HEAD HEAD; then
						# before we go all-in, let's see if master has changes to .external-pins that we *should* aggressively rebase on top of
						touchingCommits="$(git log --oneline 'FETCH_HEAD..HEAD' -- .external-pins)"
						if [ -z "$touchingCommits" ]; then
							git reset --hard FETCH_HEAD
						fi
					fi
				'''
			}

			def tags = sh(
				script: '''
					.external-pins/list.sh | gawk -F/ '$1 == ENVIRON["org"] { print }'
				''',
				returnStdout: true,
			).tokenize()

			for (def tag in tags) { withEnv(['tag=' + tag]) {
				def commit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()

				try {
					stage('Update ' + tag) {
						sh '''#!/usr/bin/env bash
							set -Eeuo pipefail -x

							file="$(.external-pins/file.sh "$tag")"
							before="$(< "$file" || :)"

							.external-pins/update.sh "$tag"

							digest="$(< "$file")"
							[ -n "$digest" ]

							if [ "$before" = "$digest" ]; then
								# bail early without changes
								exit 0
							fi

							# look up image metadata for reproducible commits (GIT_AUTHOR_DATE, GIT_COMMITTER_DATE)
							timestamp="$(
								bashbrew remote arches --json "$tag@$digest" \\
									| jq -r '.arches[][].digest' \\
									| xargs -rI'{}' docker run --rm --security-opt no-new-privileges --user "$RANDOM:$RANDOM" \\
										gcr.io/go-containerregistry/crane@sha256:f0c28591e6b2f5d659cfa3170872675e855851eef4a6576d5663e3d80162b391 \\
										config "$tag@{}" \\
									| jq -r '.created, .history[].created' \\
									| sort -u \\
									| tail -1
							)"
							if [ -n "$timestamp" ]; then
								export GIT_AUTHOR_DATE="$timestamp" GIT_COMMITTER_DATE="$timestamp"
							fi

							git add -A "$file" || :
							git commit -m "Update $tag to $digest" || :
						'''
					}
				}
				catch (err) {
					// if there's an error updating this version, make it like we were never here (and set the build as "unstable")
					withEnv(['commit=' + commit]) {
						sh '''
							git reset --hard "$commit"
							git clean -dffx
						'''
					}
					echo "ERROR while updating ${tag}: ${err}"
					// "catchError" is the only way to set "stageResult" :(
					catchError(message: "ERROR while updating ${tag}: ${err}", buildResult: 'UNSTABLE', stageResult: 'FAILURE') { error() }
				}
			} }

			stage('Push ' + org) {
				sshagent(credentials: ['docker-library-bot'], ignoreMissing: true) {
					def newCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
					if (newCommit != env.headCommit) {
						sh 'git push -f fork HEAD:refs/heads/$branch'
					}
					else {
						sh 'git push fork --delete "refs/heads/$branch"'
					}
				}
			}
		} }
	} }
}
