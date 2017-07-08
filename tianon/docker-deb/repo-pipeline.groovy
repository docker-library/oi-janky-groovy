// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'tianon/docker-deb/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

node { ansiColor('xterm') {
	dir('output') {
		deleteDir()

		for (arch in vars.arches) {
			withEnv([
				'ARCH=' + arch,
			]) {
				stage(arch) {
					sh '''
						trap 'rm "$ARCH.zip"' EXIT
						wget -O "$ARCH.zip" "https://doi-janky.infosiftr.net/job/tianon/job/docker-deb/job/$ARCH/lastSuccessfulBuild/artifact/**/*zip*/archive.zip" || exit 0
						unzip "$ARCH.zip"
					'''
				}
			}
		}

		suites = sh(returnStdout: true, script: '''#!/usr/bin/env bash
			set -Eeuo pipefail
			shopt -s nullglob
			cd pool
			echo *
		''').tokenize() as Set
		if (!suites) {
			error('No suites found!')
		}

		suiteMeta = [:]
		for (suite in suites) {
			suiteMeta[suite] = [:]
			suiteMeta[suite]['comps'] = sh(returnStdout: true, script: """#!/usr/bin/env bash
				set -Eeuo pipefail
				shopt -s nullglob
				cd 'pool/${suite}'
				echo *
			""").tokenize() as Set
			suiteMeta[suite]['arches'] = sh(returnStdout: true, script: """#!/usr/bin/env bash
				set -Eeuo pipefail
				shopt -s nullglob
				cd 'pool/${suite}'
				echo */*
			""").replaceAll(/\S+\//, '').tokenize() as Set
		}

		stage('Configure') {
			sh '''
				mkdir -p conf cache

				tee conf/apt-ftparchive.conf <<-'EOF'
					Dir {
						ArchiveDir ".";
						CacheDir "./cache";
					}

					Default {
						Packages::Compress ". xz";
						Sources::Compress ". xz";
						Contents::Compress ". xz";
					}

					TreeDefault {
						BinCacheDB "packages-$(DIST)-$(SECTION)-$(ARCH).db";

						Directory "pool/$(DIST)/$(SECTION)/$(ARCH)";
						SrcDirectory "pool/$(DIST)/$(SECTION)";

						Packages "dists/$(DIST)/$(SECTION)/binary-$(ARCH)/Packages";
						Sources "dists/$(DIST)/$(SECTION)/source/Sources";
						Contents "dists/$(DIST)/$(SECTION)/Contents-$(ARCH)";
					}
				EOF
			'''

			for (suite in suites) {
				withEnv([
					'SUITE=' + suite,
					'COMPS=' + suiteMeta[suite]['comps'].join(' '),
					'ARCHES=' + suiteMeta[suite]['arches'].join(' '),
				]) {
					sh '''
						tee -a conf/apt-ftparchive.conf <<-EOF

							Tree "${SUITE}" {
								Sections "${COMPS}";
								Architectures "${ARCHES} source";
							}
						EOF

						for comp in $COMPS; do
							for arch in $ARCHES; do
								mkdir -p "dists/$SUITE/$comp/binary-$arch"
							done
							mkdir -p "dists/$SUITE/$comp/source"
						done
					'''
				}
			}
		}

		stage('Pull') {
			sh '''
				docker pull tianon/sbuild
			'''
		}

		stage('Generate') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail

				docker run -i --rm -v "$PWD":/work -w /work -u "$(id -u):$(id -g)" tianon/sbuild bash -c '
					set -Eeuo pipefail
					set -x

					apt-ftparchive generate conf/apt-ftparchive.conf
				'
			'''

			for (suite in suites) {
				withEnv([
					'SUITE=' + suite,
					'COMPS=' + suiteMeta[suite]['comps'].join(' '),
					'ARCHES=' + suiteMeta[suite]['arches'].join(' '),
				]) {
					sh '''#!/usr/bin/env bash
						set -Eeuo pipefail

						docker run -e SUITE -e COMPS -e ARCHES -i --rm -v "$PWD":/work -w /work -u "$(id -u):$(id -g)" tianon/sbuild bash -c '
							set -Eeuo pipefail
							set -x

							apt-ftparchive \\
								-o "APT::FTPArchive::Release::Codename=$SUITE" \\
								-o "APT::FTPArchive::Release::Suite=$SUITE" \\
								-o "APT::FTPArchive::Release::Components=$COMPS" \\
								-o "APT::FTPArchive::Release::Architectures=$ARCHES source" \\
								release \\
								"dists/$SUITE" > "dists/$SUITE/Release"
						'
					'''
				}
			}

			// TODO GPG sign Release files
		}

		stage('Archive') {
			archiveArtifacts(
				artifacts: '**',
				fingerprint: true,
			)
		}
	}
} }
