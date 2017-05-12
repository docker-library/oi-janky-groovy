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

env.OPENSUSE_ARCH = vars.imagesMeta[env.ACT_ON_IMAGE]['map'][env.ACT_ON_ARCH]
if (!env.OPENSUSE_ARCH) {
	error("Unknown openSUSE architecture for '${env.ACT_ON_ARCH}'.")
}

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/official-images.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'library/' + env.ACT_ON_IMAGE,
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		checkout(
			poll: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/openSUSE/docker-containers-build.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'opensuse',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	versions = sh(returnStdout: true, script: '#!/bin/bash -e' + '''
		bashbrew cat -f '{{ range .Entries }}{{ .GitFetch }}{{ "\\n" }}{{ end }}' "$ACT_ON_IMAGE" \
			| awk -F- '$1 == "refs/heads/openSUSE" { print $2 }' \
			| sort -u
	''').trim().tokenize()

	ansiColor('xterm') {
		env.VERSIONS = ''
		for (version in versions) {
			dir('opensuse/' + version) {
				deleteDir() // make sure we start with a clean slate every time
				withEnv(
					"ROOTFS_URL=http://download.opensuse.org/repositories/Virtualization:/containers:/images:/openSUSE-${version}/images/openSUSE-${version}-docker-guest-docker.${env.OPENSUSE_ARCH}.tar.xz",
					// use upstream's exact Dockerfile as-is
					"DOCKERFILE_URL=https://raw.githubusercontent.com/openSUSE/docker-containers-build/openSUSE-${version}/docker/Dockerfile",
				) {
					stage('Prep ' + version) {
						sh '''
							curl -fL -o Dockerfile "$DOCKERFILE_URL"
						'''
					}
					targetTarball = sh(returnStdout: true, script: '''
						awk 'toupper($1) == "ADD" { print $2 }'
					''').trim() // "openSUSE-Tumbleweed.tar.xz"
					assert targetTarball.endsWith('.tar.xz') // minor sanity check
					stage('Download ' + version) {
						if (0 != sh(returnStatus: true, script: 'curl -fL -o rootfs.tar.xz "$ROOTFS_URL"')) {
							echo("Failed to download openSUSE rootfs for ${version} on ${env.OPENSUSE_ARCH}; skipping!")
							deleteDir()
							continue
						}
					}
					env.VERSIONS += ' ' + version
				}
			}
		}
		env.VERSIONS = env.VERSIONS.trim()
		stage('Commit') {
			sh '''
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'

				git add -A .
				git commit -m "Update for $ACT_ON_ARCH"
			'''
		}
		stage('Generate') {
			sh '''
				{
					echo "Maintainers: Docker Library Bot <$ACT_ON_ARCH> (@docker-library-bot),"
					echo "             $(bashbrew cat -f '{{ (first .Entries).MaintainersString }}' "$ACT_ON_IMAGE")"
					echo "Constraints: $(bashbrew cat -f '{{ (first .Entries).ConstraintsString }}' "$ACT_ON_IMAGE")"
					commit="$(git log -1 --format='format:%H')"
					for version in $VERSIONS; do
						echo
						for field in TagsString GitRepo GitFetch; do
							echo "${field%String}: $(bashbrew cat -f "{{ .TagEntry.$field }}" "$ACT_ON_IMAGE:$version")"
						done
						echo "GitCommit: $commit"
						echo "Directory: $version"
					done
				} > tmp-bashbrew
				mv -v tmp-bashbrew "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			'''
		}
		stage('Seed Cache') {
			sh '''
				# ensure the bashbrew cache directory exists, and has an initialized Git repo
				bashbrew from https://raw.githubusercontent.com/docker-library/official-images/master/library/hello-world > /dev/null

				# and fill it with our newly generated commit (so that "bashbrew build" can DTRT)
				git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:
			'''
		}

		stage('Build') {
			retry(3) {
				sh '''
					bashbrew build "$ACT_ON_IMAGE"
				'''
			}
		}

		stage('Tag') {
			sh '''
				bashbrew tag --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
			'''
		}

		stage('Push') {
			sh '''
				bashbrew push --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
			'''
		}

		dir(env.BASHBREW_CACHE) { stash name: 'bashbrew-cache' }
		dir(env.BASHBREW_LIBRARY) { stash includes: env.ACT_ON_IMAGE, name: 'bashbrew-library' }
	}
}

node('') {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	dir(env.BASHBREW_CACHE) { unstash 'bashbrew-cache' }
	dir(env.BASHBREW_LIBRARY) { unstash 'bashbrew-library' }

	stage('Checkout Docs') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/docs.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'd',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') { dir('d') {
		stage('Update Docs') {
			sh '''
				./update.sh "$TARGET_NAMESPACE/$ACT_ON_IMAGE"
			'''
		}

		stage('Diff Docs') {
			sh '''
				git diff --color
			'''
		}

		withCredentials([[
			$class: 'UsernamePasswordMultiBinding',
			credentialsId: 'docker-hub-' + env.ACT_ON_ARCH,
			usernameVariable: 'USERNAME',
			passwordVariable: 'PASSWORD',
		]]) {
			stage('Push Docs') {
				sh '''
					dockerImage="docker-library-docs:$ACT_ON_ARCH-$ACT_ON_IMAGE"
					docker build --pull -t "$dockerImage" -q .
					test -t 1 && it='-it' || it='-i'
					set +x
					docker run "$it" --rm -e TERM \
						--entrypoint './push.pl' \
						"$dockerImage" \
						--username "$USERNAME" \
						--password "$PASSWORD" \
						--batchmode \
						"$TARGET_NAMESPACE/$ACT_ON_IMAGE"
				'''
			}
		}
	} }
}
