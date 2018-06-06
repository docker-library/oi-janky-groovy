properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
])

arches = [
	// see https://partner-images.canonical.com/core/xenial/current/
	['amd64', 'amd64'],
	['arm32v7', 'armhf'],
	['arm64v8', 'arm64'],
	['i386', 'i386'],
	['ppc64le', 'ppc64el'],
	['s390x', 's390x'],
	// ['bashbrew-arch', 'dpkg-arch'],
]

env.TZ = 'UTC'

node {
	stage('Checkout') {
		checkout(
			poll: true,
			changelog: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:tianon/docker-brew-ubuntu-core.git',
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
						relativeTargetDir: 'ubuntu',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		sh '''
			cd ubuntu
			git config user.name 'Docker Library Bot'
			git config user.email 'github+dockerlibrarybot@infosiftr.com'
		'''
	}

	dir('ubuntu') {
		for (archTuple in arches) {
			arch = archTuple[0]
			dpkgArch = archTuple[1]

			withEnv([
				'ARCH=' + arch,
				'DPKG_ARCH=' + dpkgArch,
				'ARCH_BRANCH=' + ('dist-' + arch),
			]) {
				stage('Prep ' + arch) {
					sh '''
						git branch -D "$ARCH_BRANCH" || :
						git checkout -b "$ARCH_BRANCH" origin/master
						echo "$DPKG_ARCH" > arch
						git add arch
					'''
				}

				stage('Download ' + arch) {
					sh '''
						./update.sh
					'''
				}

				stage('Commit ' + arch) {
					sh '#!/bin/bash' + '''
						set -Eeuo pipefail
						set -x

						for dir in */; do
							dir="${dir%/}"
							if [ ! -f "$dir/Dockerfile" ]; then
								rm -rf "$dir"
							fi
							git add -A "$dir"
						done

						latestSerial="$(
							gawk -F '=' '$1 == "SERIAL" { print $2 }' */build-info.txt \\
								| sort -un \\
								| tail -1
						)"

						latestDate="${latestSerial%%[^0-9]*}"
						rfc2822="$(date --date "$latestDate" --rfc-2822)"
						export GIT_AUTHOR_DATE="$rfc2822"
						export GIT_COMMITTER_DATE="$GIT_AUTHOR_DATE"

						git commit --message "Update to $latestSerial for $ARCH ($DPKG_ARCH)" --message "$(./status.sh)"
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
