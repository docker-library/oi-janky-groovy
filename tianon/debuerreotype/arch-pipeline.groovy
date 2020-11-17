// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def multiarchVars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)
def vars = fileLoader.fromGit(
	'tianon/debuerreotype/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "amd64", "arm64v8", etc.
env.ACT_ON_IMAGE = 'debuerreotype'
env.TARGET_NAMESPACE = multiarchVars.archNamespace(env.ACT_ON_ARCH)
env.BUILD_ARCH = vars.buildArch[env.ACT_ON_ARCH] ?: env.ACT_ON_ARCH

env.DPKG_ARCH = multiarchVars.dpkgArches[env.ACT_ON_ARCH]
if (!env.DPKG_ARCH) {
	error("Unknown 'dpkg' architecture for '${env.ACT_ON_ARCH}'.")
}

env.debuerreotypeVersion = vars.debuerreotypeVersion
env.debuerreotypeExamplesCommit = vars.debuerreotypeExamplesCommit
env.TZ = 'UTC'

env.DOCKER_IMAGE = "debuerreotype/debuerreotype:${vars.debuerreotypeVersion}-${env.ACT_ON_ARCH}"

node(multiarchVars.node(env.BUILD_ARCH, env.ACT_ON_IMAGE)) {
	ansiColor('xterm') {
		vars.parseTimestamp(this)

		env.debuerreotypeDir = env.WORKSPACE + '/debuerreotype'
		dir(env.debuerreotypeDir) {
			deleteDir()
			stage('Download') {
				sh '''
					wget -O 'debuerreotype.tgz' "https://github.com/debuerreotype/debuerreotype/archive/${debuerreotypeVersion}.tar.gz"
					wget -O 'debuerreotype-examples.tgz' "https://github.com/debuerreotype/debuerreotype/archive/${debuerreotypeExamplesCommit}.tar.gz"
					tar -xf debuerreotype.tgz --strip-components=1
					rm -rf examples
					tar -xf debuerreotype-examples.tgz --strip-components=1 "debuerreotype-${debuerreotypeExamplesCommit}/examples"
					rm -f debuerreotype*.tgz
					./scripts/debuerreotype-version

					sed -ri "s!^FROM debian!FROM $TARGET_NAMESPACE/debian!" Dockerfile

					# temporarily resolve chicken and egg (https://lists.debian.org/debian-stable-announce/2019/07/msg00000.html)
					echo 'RUN apt-get update -qq && apt-get install -yqq debian-archive-keyring && rm -rf /var/lib/apt/lists/*' >> Dockerfile
					# TODO find a better solution for this in debuerreotype's scripts (https://github.com/debuerreotype/debuerreotype/issues/64)
				'''
			}
		}

		env.targetDir = env.WORKSPACE + '/debian'
		env.artifactsDir = env.targetDir + '/' + env.serial + '/' + env.DPKG_ARCH
		dir(env.targetDir) {
			deleteDir()

			stage('Build') {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail -x

					docker build --pull --tag "$DOCKER_IMAGE" "$debuerreotypeDir"

					args=(
						--init
						--interactive
						--rm

						--cap-add SYS_ADMIN
						--cap-drop SETFCAP

						# --debian-eol potato wants to run "chroot ... mount ... /proc" which gets blocked (i386, ancient binaries, blah blah blah)
						--security-opt seccomp=unconfined
						# (other arches see this occasionally too)

						# AppArmor blocks mount :)
						--security-opt apparmor=unconfined

						--tmpfs /tmp:dev,exec,suid,noatime
						--workdir /tmp/workdir
						--env TMPDIR=/tmp

						--mount "type=bind,src=$debuerreotypeDir/examples,dst=/examples,ro"
					)

					if [ -T 0 ] && [ -T 1 ]; then
						args+=( --tty )
					fi

					mkdir -p "$artifactsDir"
					echo "$debuerreotypeVersion" > "$artifactsDir/debuerreotype-version"
					echo "$epoch" > "$artifactsDir/debuerreotype-epoch"
					echo "$serial" > "$artifactsDir/serial"
					echo "$DPKG_ARCH" > "$artifactsDir/dpkg-arch"

					docker run "${args[@]}" "$DOCKER_IMAGE" /examples/debian-all.sh --arch="$DPKG_ARCH" . "@$epoch"
				'''
			}
		}
		dir(env.artifactsDir) {
			stage('Archive') {
				archiveArtifacts(
					artifacts: '**',
					fingerprint: true,
				)
			}
		}
	}
}
