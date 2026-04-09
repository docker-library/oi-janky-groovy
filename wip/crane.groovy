// https://doi-janky.infosiftr.net/job/wip/job/crane

// https://github.com/google/go-containerregistry/releases
def craneVersion = '0.17.0'

def golangImage = 'golang:1.21'

def arches = '''
	amd64
	arm32v5
	arm32v6
	arm32v7
	arm64v8
	i386
	mips64le
	ppc64le
	riscv64
	s390x
	windows-amd64
	windows-arm64v8
'''.tokenize()

node {
	stage('Checkout') {
		checkout scmGit(
			userRemoteConfigs: [[url: 'https://github.com/docker-library/bashbrew.git']],
			branches: [[name: '*/master']],
			extensions: [
				[$class: 'RelativeTargetDirectory', relativeTargetDir: 'bashbrew'],
				cleanAfterCheckout(deleteUntrackedNestedRepositories: true),
			],
		)

		checkout scmGit(
			userRemoteConfigs: [[url: 'https://github.com/google/go-containerregistry.git']],
			branches: [[name: 'refs/tags/v' + craneVersion]],
			extensions: [
				[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ggcr'],
				cleanAfterCheckout(deleteUntrackedNestedRepositories: true),
			],
		)
	}

	stage('Patches') {
		sh '''
			# Jon is a gem and hacked up support for "--mirror" just for Tianon 🥹😭❤
			# https://github.com/google/go-containerregistry/compare/main...jonjohnsonjr:go-containerregistry:mirror-poc
			# also needed https://github.com/google/go-containerregistry/commit/4a688a56261e07633fb92e89af28997b3a8add00
			# ie https://github.com/google/go-containerregistry/compare/4a688a56261e07633fb92e89af28997b3a8add00
			wget -O mirror.patch 'https://github.com/google/go-containerregistry/compare/55ffb00...4a688a56261e07633fb92e89af28997b3a8add00.patch'
			# we also never ever want fallback tags; https://github.com/google/go-containerregistry/commit/38b69ff497f02cb384eba8fd292f1e732684560b
			wget -O never-fallback.patch 'https://github.com/google/go-containerregistry/commit/38b69ff497f02cb384eba8fd292f1e732684560b.patch'
			git -C ggcr apply "$PWD/mirror.patch" "$PWD/never-fallback.patch"

			# it would also be fair to revert https://github.com/google/go-containerregistry/pull/1701 because we don't use JFrog, but with the above patch it really does not matter (since our pulls and pushes go to separate servers)
		'''
	}

	env.GOLANG_IMAGE = golangImage
	stage('Pull') {
		sh 'docker pull "$GOLANG_IMAGE"'
	}

	for (arch in arches) {
		withEnv(['bashbrewArch=' + arch]) {
			stage(arch) {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail -x

					mkdir -p ggcr/bin

					user="$(id -u):$(id -g)"
					args=( --rm --user "$user" --security-opt no-new-privileges --interactive )
					if [ -t 0 ] && [ -t 1 ]; then
						args+=( --tty )
					fi

					args+=(
						--env bashbrewArch
						--mount type=bind,src="$PWD",dst=/pwd
						--workdir /pwd

						--tmpfs /tmp
						--env HOME=/tmp
					)

					docker run "${args[@]}" "$GOLANG_IMAGE" bash -Eeuo pipefail -xc '
						goenv="$(bashbrew/scripts/bashbrew-arch-to-goenv.sh "$bashbrewArch")"
						eval "$goenv"
						[ -n "$GOOS" ]
						[ -n "$GOARCH" ]

						cd ggcr

						ext=""
						ldflags="-d -w"
						if [ "$GOOS" = 'windows' ]; then
							ext=".exe"
							ldflags="-w" # /usr/local/go/pkg/tool/linux_amd64/link: dynamic linking required on windows; -d flag cannot be used
						fi

						CGO_ENABLED=0 go build -o "bin/crane-$bashbrewArch$ext" -trimpath -ldflags "$ldflags" ./cmd/crane
					'
				'''
			}
		}
	}

	stage('Test') {
		sh '''
			hostArch="$(bashbrew/scripts/bashbrew-host-arch.sh)"
			"ggcr/bin/crane-$hostArch" version
		'''
	}

	dir('ggcr/bin') { stage('Archive') {
		archiveArtifacts(
			artifacts: '**',
			fingerprint: true,
		)
	} }
}
