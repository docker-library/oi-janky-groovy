// properties are set via "generate-pipeline.groovy" (jobDsl)

// TODO rebuild all arches and then remove this DPKG_ARCH hackery (since then the artifacts will include the "dpkg-arch" file directly)
def multiarchVars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'tianon/debuerreotype/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.TZ = 'UTC'

node {
	stage('Checkout') {
		checkout(
			poll: true,
			changelog: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:debuerreotype/docker-debian-artifacts.git',
					credentialsId: 'docker-library-bot',
					name: 'origin',
					refspec: '+refs/heads/master:refs/remotes/origin/master',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CloneOption',
						honorRefspec: true,
						noTags: true,
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'debian-artifacts',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		sh '''
			cd debian-artifacts
			git config user.name 'Docker Library Bot'
			git config user.email 'github+dockerlibrarybot@infosiftr.com'
		'''
	}

	dir('debian-artifacts') {
		for (arch in vars.arches) {
			withEnv([
				'ARCH=' + arch,
				'ARCH_BRANCH=' + ('dist-' + arch),
			]) {
				// TODO remove this crap
				env.DPKG_ARCH = multiarchVars.dpkgArches[arch]
				if (!env.DPKG_ARCH) {
					error("Unknown 'dpkg' architecture for '${arch}'.")
				}
				stage('Prep ' + arch) {
					sh '''
						git branch -D "$ARCH_BRANCH" || :
						git checkout -b "$ARCH_BRANCH" origin/master
						echo "$ARCH" > arch
						echo "$DPKG_ARCH" > dpkg-arch # TODO REMOVE THIS LINE WITH THE SECTION ABOVE AFTER THE NEXT REBUILD OF THE TARBALLS
					'''
				}

				stage('Download ' + arch) {
					sh '''
						./download.sh
					'''
				}

				// TODO fingerprint files again for "threading the needle" ?

				stage('Commit ' + arch) {
					sh '#!/bin/bash' + '''
						set -Eeuo pipefail
						set -x

						git add -A .

						serial="$(< serial)"

						# set explicit timestamps to try to get 100% reproducible commit hashes (given a master commit we're based on)
						rfc2822="$(date --date "$serial" --rfc-2822)"
						export GIT_AUTHOR_DATE="$rfc2822"
						export GIT_COMMITTER_DATE="$GIT_AUTHOR_DATE"

						git commit --message "Update to $serial for $ARCH (debuerreotype $(< debuerreotype-version))"
					'''
				}

				sshagent(['docker-library-bot']) {
					stage('Push ' + arch) {
						sh '''
							git push -f origin "$ARCH_BRANCH":"$ARCH_BRANCH"
						'''
					}
				}
			}
		}
	}
}
