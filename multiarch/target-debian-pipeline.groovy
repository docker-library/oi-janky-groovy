// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

// setup environment variables, etc.
vars.prebuildSetup(this)

env.DPKG_ARCH = vars.dpkgArches[env.ACT_ON_ARCH]
if (!env.DPKG_ARCH) {
	error("Unknown 'dpkg' architecture for '${env.ACT_ON_ARCH}'.")
}

// https://github.com/debuerreotype/debuerreotype/releases
//env.debuerreotypeVersion = '0.1'
env.debuerreotypeVersion = 'ec828bfb82ec28629dc3ba0c517637fb81a3bc28' // several good PRs since 0.1

env.TZ = 'UTC'

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		dir('debian') {
			deleteDir()
			sh '''
				git init --shared
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'
				git commit --allow-empty -m 'Initial commit'
			'''
		}
	}

	ansiColor('xterm') {
		env.debuerreotypeDir = env.WORKSPACE + '/debuerreotype'
		dir(env.debuerreotypeDir) {
			deleteDir()
			stage('Download') {
				sh '''
					wget -O 'debuerreotype.tgz' "https://github.com/debuerreotype/debuerreotype/archive/${debuerreotypeVersion}.tar.gz"
					tar -xf debuerreotype.tgz --strip-components=1
					rm -f debuerreotype.tgz
					./scripts/debuerreotype-version

					sed -ri "s!^FROM debian!FROM $TARGET_NAMESPACE/debian!" Dockerfile
				'''
			}
		}

		dir('debian') {
			stage('Build') {
				sh '''
					echo "$debuerreotypeVersion" > debuerreotype-version
					epoch="$(date --date 'today 00:00:00' +%s)"
					timestamp="@$epoch"
					serial="$(date --date "$timestamp" +%Y%m%d)"
					echo "$serial" > serial
					"$debuerreotypeDir/build-all.sh" . "$timestamp"
				'''
			}
			env.SERIAL = readFile('serial').trim()
			// whichever suite "stable" points to should get tagged as latest
			env.LATEST = sh(returnStdout: true, script: '''
				mirror="$("$debuerreotypeDir/scripts/.snapshot-url.sh" "$SERIAL")"
				wget -qO- "$mirror/dists/stable/Release" \
					| tac|tac \
					| awk -F ': ' '$1 == "Codename" { print $2; exit }'
			''').trim()
			stage('Prep') {
				sh '''
					mirror="$("$debuerreotypeDir/scripts/.snapshot-url.sh" "$SERIAL")"

					for dir in "$SERIAL"/*/; do
						dir="${dir%/}"
						suite="$(basename "$dir")"

						[ -f "$dir/rootfs-$DPKG_ARCH.tar.xz" ]
						tee "$dir/Dockerfile" <<-EOF
							FROM scratch
							ADD rootfs-$DPKG_ARCH.tar.xz /
							CMD ["bash"]
						EOF
						if [ -f "$dir/slim/rootfs-$DPKG_ARCH.tar.xz" ]; then
							cp -av "$dir/Dockerfile" "$dir/slim/Dockerfile"
						fi

						# check whether xyz-backports exists at this epoch
						if wget --quiet --spider "$mirror/dists/${suite}-backports/main/binary-$DPKG_ARCH/Packages.gz"; then
							mkdir -p "$dir/backports"
							tee "$dir/backports/Dockerfile" <<-EOF
								FROM $ACT_ON_IMAGE:$suite
								RUN echo 'deb http://deb.debian.org/debian ${suite}-backports main' > /etc/apt/sources.list.d/backports.list
							EOF
						fi
					done

					for exp in experimental=unstable rc-buggy=sid; do
						suite="${exp%%=*}"
						base="${exp#${suite}=}"
						dir="$SERIAL/$suite"
						[ ! -d "$dir" ]
						mkdir -p "$dir"
						tee "$dir/Dockerfile" <<-EOF
							FROM $ACT_ON_IMAGE:$base
							RUN echo 'deb http://deb.debian.org/debian $suite main' > /etc/apt/sources.list.d/experimental.list
						EOF
					done
				'''
			}

			stage('Commit') {
				sh '''
					git add -A .
					git commit -m "Build for $ACT_ON_ARCH"
				'''
			}
			vars.seedCache(this)

			stage('Generate') {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail
					{
						echo 'Maintainers: Tianon Gravi <tianon@debian.org> (@tianon)'
						echo "GitRepo: https://doi-janky.infosiftr.net" # obviously bogus
						commit="$(git log -1 --format='format:%H')"
						echo "GitCommit: $commit"

						for suiteDir in "$SERIAL"/*/; do
							suiteDir="${suiteDir%/}"
							suite="$(basename "$suiteDir")"

							[ -f "$suiteDir/Dockerfile" ] || continue

							tags="$suite"

							# only add "suite-SERIAL" as an alias for debuerreotype tags (no soup for experimental/rc-buggy)
							if [ -f "$suiteDir/rootfs-$DPKG_ARCH.debuerreotype-epoch" ]; then
								tags+=", ${suite}-${SERIAL}"
							fi

							# version number aliases
							case "$suite" in
								sid|testing|*stable|experimental|rc-buggy) ;;
								*)
									if [ -s "$suiteDir/rootfs-$DPKG_ARCH.debian_version" ]; then
										debianVersion="$(< "$suiteDir/rootfs-$DPKG_ARCH.debian_version")"
										if [[ "$debianVersion" != */sid ]]; then
											while [ "$debianVersion" != "${debianVersion%.*}" ]; do
												tags+=", $debianVersion"
												debianVersion="${debianVersion%.*}"
											done
											tags+=", $debianVersion"
										fi
									fi
									;;
							esac

							if [ "$suite" = "$LATEST" ]; then
								tags+=', latest'
							fi

							echo
							echo "Tags: $tags"
							echo "Directory: $suiteDir"

							for variant in slim backports; do
								if [ -f "$suiteDir/$variant/Dockerfile" ]; then
									echo
									echo "Tags: ${suite}-${variant}"
									echo "Directory: ${suiteDir}/${variant}"
								fi
							done
						done
					} > tmp-bashbrew
					set -x
					mkdir -p "$BASHBREW_LIBRARY"
					mv -v tmp-bashbrew "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
					cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
					bashbrew cat "$ACT_ON_IMAGE"
					bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
				'''
			}
		}

		vars.bashbrewBuildAndPush(this)

		// TODO vars.stashBashbrewBits(this)
	}
}

// TODO vars.docsBuildAndPush(this)
