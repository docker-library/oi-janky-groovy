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
	env.BRANCH_BASE = repoMeta['branch-base']
	env.BRANCH_PUSH = repoMeta['branch-push']

	env.BASHBREW_CACHE = workspace + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = workspace + '/oi/library'
	env.BASHBREW_NAMESPACE = 'update.sh'
	dir(env.BASHBREW_CACHE) { deleteDir() }
	sh 'mkdir -p "$BASHBREW_CACHE"'

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
			git -C oi config user.email 'github+dockerlibrarybot@infosiftr.com'
		'''

		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail -x

			# https://github.com/moby/moby/issues/30973 🤦
			# docker build --pull --tag oisupport/update.sh 'https://github.com/docker-library/oi-janky-groovy.git#:update.sh'
			tempDir="$(mktemp -d -t docker-build-janky-XXXXXXXXXX)"
			trap 'rm -rf "$tempDir"' EXIT
			git clone --depth 1 https://github.com/docker-library/oi-janky-groovy.git "$tempDir"
			docker build --pull --tag oisupport/update.sh "$tempDir/update.sh"

			# precreate the bashbrew cache (so we can get creative with "$BASHBREW_CACHE/git" later)
			bashbrew --arch amd64 from --uniq --apply-constraints hello-world:linux > /dev/null
		'''
	}

	ansiColor('xterm') { dir('oi') {
		def initialCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()

		// TODO filter this list by namespace/repo when we split this job
		def tags = sh(
			script: '''
				.external-pins/list.sh
			''',
			returnStdout: true,
		).tokenize()

		for (def tag in tags) { withEnv(['tag=' + tag]) {
			def commit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()

			try {
				stage('Update ' + tag) {
					sh '''#!/usr/bin/env bash
						set -Eeuo pipefail -x

						.external-pins/update.sh "$tag"

						file="$(.external-pins/file.sh "$tag")"
						digest="$(< "$file")"
						[ -s "$digest" ]

						# TODO look up image metadata for reproducible commits (GIT_AUTHOR_DATE, GIT_COMMITTER_DATE)

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

		stage('Push') {
			sshagent(['docker-library-bot']) {
				def newCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
				if (newCommit != initialCommit) {
					// TODO somehow make sure we don't repeatedly update this branch every time "master" changes
					sh 'git push -f fork HEAD:refs/heads/$repo'
				}
				else {
					sh 'git push fork --delete "refs/heads/$repo"'
				}
			}
		}
	} }
}
