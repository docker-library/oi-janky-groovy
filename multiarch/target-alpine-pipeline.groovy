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

env.APK_ARCH = vars.imagesMeta[env.ACT_ON_IMAGE]['map'][env.ACT_ON_ARCH]
if (!env.APK_ARCH) {
	error("Unknown Alpine Linux architecture for '${env.ACT_ON_ARCH}'.")
}

versions = [
	// https://wiki.alpinelinux.org/wiki/Alpine_Linux:Releases
	// https://github.com/docker-library/official-images/blob/master/library/alpine
	// http://dl-cdn.alpinelinux.org/alpine/v3.5/releases/aarch64/latest-releases.yaml
	'3.6', // no releases yet
	'3.5',
	//'3.4', // no minirootfs artifacts
	//'3.3', // no minirootfs artifacts
	'edge', // no releases
]

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
		dir('alpine') {
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
		dir('alpine') {
			env.VERSIONS = ''
			for (version in versions) {
				baseUrl = "http://dl-cdn.alpinelinux.org/alpine/v${version}/releases/${env.APK_ARCH}"
				dir(version) {
					deleteDir() // make sure we start with a clean slate every time
					if (0 != sh(returnStatus: true, script: """
						curl -fL -o latest-releases.yaml '${baseUrl}/latest-releases.yaml'
					""")) {
						echo("Failed to download Alpine Linux 'latest-releases' file for ${version} on ${env.APK_ARCH}; skipping!")
						deleteDir()
					}
					else {
						latestReleases = readYaml(file: 'latest-releases.yaml')
						rootfsFile = ''
						rootfsSha256 = ''
						for (release in latestReleases) {
							if (release.flavor == 'alpine-minirootfs') {
								assert release.file
								rootfsFile = release.file
								assert release.sha256
								rootfsSha256 = release.sha256
								break
							}
						}
						if (!rootfsFile) {
							echo("Failed to find 'minirootfs' release for ${version} on ${env.APK_ARCH}; skipping!")
							deleteDir()
						}
						else {
							withEnv([
								"ROOTFS_FILE=${rootfsFile}",
								"ROOTFS_URL=${baseUrl}/${rootfsFile}",
								"ROOTFS_SHA256=${rootfsSha256}",
							]) {
								stage('Prep ' + version) {
									sh '''
										tee Dockerfile <<-EODF
											FROM scratch
											ADD $ROOTFS_FILE /
											CMD ["sh"]
										EODF
									'''
								}
								stage('Download ' + version) {
									if (0 != sh(returnStatus: true, script: '''
										curl -fL -o "$ROOTFS_FILE" "$ROOTFS_URL"
									''')) {
										echo("Failed to download Alpine Linux rootfs for ${version} on ${env.APK_ARCH}; skipping!")
										deleteDir()
									}
									else {
										assert 0 == sh(returnStatus: true, script: '''
											echo "$ROOTFS_SHA256 *$ROOTFS_FILE" | sha256sum -c
										''')
										env.VERSIONS += ' ' + version
									}
								}
							}
						}
					}
				}
			}
			env.VERSIONS = env.VERSIONS.trim()

			stage('Commit') {
				sh '''
					git add -A $VERSIONS
					git commit -m "Update for $ACT_ON_ARCH"
					git clean -dfx
					git checkout -- .
				'''
			}
			vars.seedCache(this)

			stage('Generate') {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail
					{
						echo 'Maintainers: Tianon (@tianon)'
						commit="$(git log -1 --format='format:%H')"
						for version in $VERSIONS; do
							echo
							for field in TagsString; do
								val="$(bashbrew cat -f "{{ .TagEntry.$field }}" "$ACT_ON_IMAGE:$version")"
								echo "${field%String}: $val"
							done
							echo "GitRepo: https://doi-janky.infosiftr.net" # obviously bogus
							echo "GitCommit: $commit"
							echo "Directory: $version"
						done
					} > tmp-bashbrew
					set -x
					mv -v tmp-bashbrew "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
					cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
					bashbrew cat "$ACT_ON_IMAGE"
					bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
				'''
			}
		}

		vars.createFakeBashbrew(this)
		vars.bashbrewBuildAndPush(this)

		vars.stashBashbrewBits(this)
	}
}

vars.docsBuildAndPush(this)
