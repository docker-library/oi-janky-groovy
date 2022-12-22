// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def multiarchVars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)
def vars = fileLoader.fromGit(
	'tianon/debuerreotype/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "amd64", "arm64v8", etc.
env.ACT_ON_IMAGE = 'debuerreotype'
env.TARGET_NAMESPACE = multiarchVars.archNamespace(env.ACT_ON_ARCH)
env.BUILD_ARCH = vars.buildArch[env.ACT_ON_ARCH] ?: env.ACT_ON_ARCH

env.DPKG_ARCH = multiarchVars.dpkgArches[env.ACT_ON_ARCH]
if (!env.DPKG_ARCH) {
	error("Unknown 'dpkg' architecture for '${env.ACT_ON_ARCH}'.")
}

switch (env.DPKG_ARCH) {
	case [
		// https://ports.debian.org/ (ports architectures only support unstable)
		'riscv64',
	]:
		env.DEBIAN_PORTS = '1'
		break

	default:
		env.DEBIAN_PORTS = ''
		break
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
					tar -xf debuerreotype.tgz --strip-components=1
					if [ "$debuerreotypeExamplesCommit" != "$debuerreotypeVersion" ]; then
						wget -O 'debuerreotype-examples.tgz' "https://github.com/debuerreotype/debuerreotype/archive/${debuerreotypeExamplesCommit}.tar.gz"
						rm -rf examples
						tar -xf debuerreotype-examples.tgz --strip-components=1 \
							"debuerreotype-${debuerreotypeExamplesCommit}/.docker-image.sh" \
							"debuerreotype-${debuerreotypeExamplesCommit}/.dockerignore" \
							"debuerreotype-${debuerreotypeExamplesCommit}/Dockerfile" \
							"debuerreotype-${debuerreotypeExamplesCommit}/docker-run.sh" \
							"debuerreotype-${debuerreotypeExamplesCommit}/examples"
					fi
					rm -f debuerreotype*.tgz
					./scripts/debuerreotype-version

					sed -ri "s!^FROM debian${DEBIAN_PORTS:+:[^-]+}!FROM $TARGET_NAMESPACE/debian${DEBIAN_PORTS:+:unstable}!" Dockerfile

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

					mkdir -p "$targetDir"

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

						--mount "type=bind,src=$targetDir,dst=/output"
					)

					if [ -t 0 ] && [ -t 1 ]; then
						args+=( --tty )
					fi

					mkdir -p "$artifactsDir"
					echo "$debuerreotypeVersion" > "$artifactsDir/debuerreotype-version"
					echo "$epoch" > "$artifactsDir/debuerreotype-epoch"
					echo "$serial" > "$artifactsDir/serial"
					echo "$DPKG_ARCH" > "$artifactsDir/dpkg-arch"

					args+=( "$DOCKER_IMAGE" )

					if [ -n "${DEBIAN_PORTS:-}" ]; then
						args+=( /examples/debian.sh --ports --arch="$DPKG_ARCH" --codename-copy /output unstable "@$epoch" )
					else
						args+=( /examples/debian-all.sh --arch="$DPKG_ARCH" /output "@$epoch" )
					fi
					docker run "${args[@]}"
				'''
			}

			stage('OCI') {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail -x
					shopt -s globstar

					for rootfs in "$artifactsDir"/**/rootfs.tar.xz; do
						dir="$(dirname "$rootfs")"
						"$debuerreotypeDir/examples/oci-image.sh" "$dir/oci.tar" "$dir"
					done
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
