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
env.debuerreotypeVersion = '4e6bdec185889624b44cbe058f41a1fe67056a3b' // several good PRs since 0.1

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
			stage('Prep') {
				sh '''
					for dir in "$SERIAL"/*/ "$SERIAL"/*/slim/; do
						[ -f "$dir/rootfs-$DPKG_ARCH.tar.xz" ]
						tee "$dir/Dockerfile" <<-EOF
							FROM scratch
							ADD rootfs-$DPKG_ARCH.tar.xz /
							CMD ["bash"]
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

							echo
							echo "Tags: $suite, ${suite}-${SERIAL}"
							echo "Directory: $suiteDir"

							if [ -d "$suiteDir/slim" ]; then
								echo
								echo "Tags: ${suite}-slim"
								echo "Directory: ${suiteDir}/slim"
							fi
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
