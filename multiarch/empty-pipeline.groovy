// properties are set via "generate-pipeline.groovy" (jobDsl)

// This file is meant for build jobs that have transistioned to the new builder (so that trigger children can trigger an empty job)
env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc
node {
	stage('Checkout') {
		checkout(scmGit(
			userRemoteConfigs: [[
				url: 'https://github.com/docker-library/meta.git',
				name: 'origin',
			]],
			branches: [[name: '*/main']], // TODO back to main
			extensions: [
				submodule(
					parentCredentials: true,
					recursiveSubmodules: true,
					trackingSubmodules: true,
				),
				cleanBeforeCheckout(),
				cleanAfterCheckout(),
				[$class: 'RelativeTargetDirectory', relativeTargetDir: 'meta'],
			],
		))
	}

	dir('.bin') {
		deleteDir()

		stage('Crane') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				# TODO if this job ever runs on a non-amd64 node, we need to revisit this bit
				export BASHBREW_ARCH='amd64'

				ext=''
				if [ "$BASHBREW_ARCH" = 'windows-amd64' ]; then
					ext='.exe'
				fi

				# https://doi-janky.infosiftr.net/job/wip/job/crane
				wget --timeout=5 -O "crane$ext" "https://doi-janky.infosiftr.net/job/wip/job/crane/lastSuccessfulBuild/artifact/crane-$BASHBREW_ARCH$ext" --progress=dot:giga
				# TODO checksum verification ("checksums.txt")
				chmod +x "crane$ext"
				"./crane$ext" version
			'''
			env.PATH = "${workspace}/.bin:${env.PATH}"
		}
	}

	// make sure "docker login" is localized to this workspace
	env.DOCKER_CONFIG = workspace + '/.docker'
	dir(env.DOCKER_CONFIG) { deleteDir() }

	withCredentials([usernamePassword(
		credentialsId: 'docker-hub-readonly',
		usernameVariable: 'DOCKER_USERNAME',
		passwordVariable: 'DOCKER_PASSWORD',
	)]) {
		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail
			docker login --username "$DOCKER_USERNAME" --password-stdin <<<"$DOCKER_PASSWORD"
		'''
	}

	stage('Build-Info') {
		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail -x

			rm -rf build-info
			mkdir build-info

			git -C meta/.doi rev-parse HEAD > build-info/commit.txt

			mkdir build-info/image-ids

			shell="$(
				jq -r \
					--arg image "$ACT_ON_IMAGE" \
					--arg arch "$ACT_ON_ARCH" \
					'
						.[]
						| select(
							.build.arch == $arch
							and .build.resolved != null
							and (.build.resolved.manifests | length > 0)
							and any(.source.arches[.build.arch].tags[]; startswith($image + ":"))
						)
						| @sh "crane manifest \\(.build.resolved.manifests[0].annotations["org.opencontainers.image.ref.name"]) | jq -r \\(".config.digest") | tee \\([ "build-info/image-ids/" + (.source.arches[.build.arch].tags[] | select(startswith($image + ":")) | sub(":"; "_")) + ".txt" ])"
					' meta/builds.json
			)"
			eval "$shell"
		'''
	}

	// "docker logout"
	dir(env.DOCKER_CONFIG) { deleteDir() }

	stage('Archive') {
		archiveArtifacts 'build-info/**'
	}
}
