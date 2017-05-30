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

env.FEDORA_ARCH = vars.imagesMeta[env.ACT_ON_IMAGE]['map'][env.ACT_ON_ARCH]
if (!env.FEDORA_ARCH) {
	error("Unknown Fedora architecture for '${env.ACT_ON_ARCH}'.")
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
		dir('fedora') {
			deleteDir()
			sh '''
				git init --shared
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'
				git commit --allow-empty -m 'Initial commit'
			'''
		}
	}

	versions = sh(returnStdout: true, script: '#!/bin/bash -e' + '''
		bashbrew cat -f '{{ range .Entries }}{{ .GitFetch }}{{ "\\n" }}{{ end }}' "$ACT_ON_IMAGE" \
			| awk -F/ '$1 == "refs" && $2 == "heads" && $3 ~ /^[0-9]+$/ { print $3 }' \
			| sort -u
	''').trim().tokenize()

	ansiColor('xterm') {
		dir('fedora') {
			env.VERSIONS = ''
			for (version in versions) {
				dir(version) {
					deleteDir() // make sure we start with a clean slate every time

					url = sh(returnStdout: true, script: '#!/bin/bash -Eeuo pipefail' + """
						for folder in fedora fedora-secondary; do
							urlBase="https://dl.fedoraproject.org/pub/\$folder"
							possibles="$(
								curl -fsSL "\$urlBase/imagelist-\$folder" \\
									| grep -E '^([.]/)?(linux/)?releases/${version}/Docker/${env.FEDORA_ARCH}/.*[.]tar[.]xz\$' \\
									| sort -ruV
							)"
							if [ -n "\$possibles" ]; then
								for possible in \$possibles; do
									echo "\$urlBase/\$possible"
									exit
								done
							fi
						done
					""").trim()

					if (url) {
						withEnv([
							'DOCKER_SAVE_URL=' + url,
							// use upstream's exact Dockerfile as-is
							"DOCKERFILE_URL=https://raw.githubusercontent.com/fedora-cloud/docker-brew-fedora/${version}/Dockerfile",
						]) {
							stage('Prep ' + version) {
								sh '''
									curl -fL -o Dockerfile "$DOCKERFILE_URL"
								'''
							}
							targetTarball = sh(returnStdout: true, script: '''
								awk 'toupper($1) == "ADD" { print $2 }' Dockerfile
							''').trim() // "fedora-24-20160815.tar.xz"
							assert targetTarball.endsWith('.tar.xz') // minor sanity check
							stage('Download ' + version) {
								if (0 != sh(returnStatus: true, script: """
									dockerSaveTarball="\$(basename "\$DOCKER_SAVE_URL")"
									curl -fL -o "\$dockerSaveTarball" "\$DOCKER_SAVE_URL"
									tar -xvf "\$dockerSaveTarball" \\
										--strip-components 1 \\
										--wildcards --wildcards-match-slash \\
										'*/layer.tar'
									[ -f layer.tar ]
									rm "\$dockerSaveTarball"
									xz --compress -9 layer.tar
									[ -f layer.tar.xz ]
									rm layer.tar
									mv layer.tar.xz '${targetTarball}'
								""")) {
									echo("Failed to download Fedora rootfs for ${version} on ${env.FEDORA_ARCH}; skipping!")
									deleteDir()
								}
							}
							if (fileExists('Dockerfile')) {
								env.VERSIONS += ' ' + version
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
						for field in MaintainersString ConstraintsString; do
							val="$(bashbrew cat -f "{{ (first .Entries).$field }}" "$ACT_ON_IMAGE")"
							echo "${field%String}: $val"
						done
						echo "GitRepo: https://doi-janky.infosiftr.net" # obviously bogus
						commit="$(git log -1 --format='format:%H')"
						echo "GitCommit: $commit"
						for version in $VERSIONS; do
							echo
							for field in TagsString; do
								val="$(bashbrew cat -f "{{ .TagEntry.$field }}" "$ACT_ON_IMAGE:$version")"
								echo "${field%String}: $val"
							done
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
