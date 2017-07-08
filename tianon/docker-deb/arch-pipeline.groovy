// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def multiarchVars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "amd64", "arm64v8", etc.

node(multiarchVars.node(env.ACT_ON_ARCH, 'sbuild')) {
	stage('Checkout') {
		checkout(
			poll: false,
			changelog: false,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/tianon/dockerfiles.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'tianon-dockerfiles',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		dir('sources') {
			deleteDir()
		}
		dir('output') {
			deleteDir()
		}
	}

	stage('Download') { dir('sources') {
		sh '''
			wget -O sources.zip 'https://doi-janky.infosiftr.net/job/tianon/job/docker-deb/job/source/lastSuccessfulBuild/artifact/*zip*/archive.zip'
			unzip sources.zip
			rm sources.zip
			mv archive/* ./
			rmdir archive
		'''
	} }

	dir('tianon-dockerfiles/sbuild') { ansiColor('xterm') {
		stage('Pull') {
			sh '''
				awk -v arch="$ACT_ON_ARCH" 'toupper($1) == "FROM" { $2 = arch "/" $2 } { print }' Dockerfile > Dockerfile.new
				mv Dockerfile.new Dockerfile
				awk 'toupper($1) == "FROM" { print $2 }' Dockerfile \\
					| xargs -rtn1 docker pull \\
					|| true
			'''
		}
		stage('Build') {
			sh '''
				docker build -t tianon/sbuild .
			'''
		}
	} }

	// "findFiles" causes "java.io.NotSerializableException: java.util.AbstractList$Itr" (intermittently)
	changesFiles = sh(returnStdout: true, script: '''#!/usr/bin/env bash
		set -Eeuo pipefail
		shopt -s nullglob
		echo sources/*_source.changes
	''').tokenize()
	if (!changesFiles) {
		error('No files matching "*_source.changes" found!')
	}

	for (changesFile in changesFiles) {
		changesFile = changesFile as String // convert "org.jenkinsci.plugins.pipeline.utility.steps.fs.FileWrapper" to String

		dscFile = changesFile.replaceAll(/_source[.]changes$/, '.dsc')

		// "fileExists" causes "java.io.NotSerializableException: java.util.AbstractList$Itr" (intermittently)
		if (0 != sh(returnStatus: true, script: "test -f '${dscFile}'")) {
			error("DSC file '${dscFile}' does not exist!")
		}

		// "readFile" causes "java.io.NotSerializableException: java.util.AbstractList$Itr" (intermittently)
		suite = sh(returnStdout: true, script: """
			awk -F ': ' '\$1 == "Distribution" { print \$2; exit }' '${changesFile}'
		""").trim()
		if (!suite) {
			error("Failed to determine suite for '${changesFile}'!")
		}

		if (suite == 'xenial' || suite == 'zesty') {
			// TODO ubuntu
			continue
		}

		stage(suite) {
			withEnv([
				'DSC=' + dscFile,
				'SUITE=' + suite,
			]) {
				sh '''
					docker run -i --rm \\
						-v "$PWD":/work \\
						-w /work \\
						-e CHOWN="$(id -u):$(id -g)" \\
						-e DSC -e SUITE \\
						--cap-add SYS_ADMIN \\
						tianon/sbuild bash -c '
							set -Eeuo pipefail
							set -x

							dpkgArch="$(dpkg --print-architecture)"

							# TODO handle ubuntu
							download-debuerreotype-tarball.sh "$SUITE" "$dpkgArch"

							sbuildArgs=(
								--verbose
								--dist "$SUITE"
								--arch "$dpkgArch"
								--no-source
								--arch-any
							)

							case "$dpkgArch" in
								amd64) sbuildArgs+=( --arch-all ) ;;
								*) sbuildArgs+=( --no-arch-all ) ;;
							esac

							case "$SUITE" in
								jessie)
									sbuildArgs+=(
										--build-dep-resolver aptitude
										--extra-repository "deb http://deb.debian.org/debian ${SUITE}-backports main"
									)
									;;
							esac

							# attempt to avoid "java.nio.file.AccessDeniedException" on failed builds (root-owned files)
							# (see "dir(output) { deleteDir }" above)
							mkdir -p output
							chown -R "$CHOWN" output
							chmod g+srwX output

							DSC="$(readlink -f "$DSC")"
							cd output
							sbuild "${sbuildArgs[@]}" "$DSC"
							chown -R "$CHOWN" .
						'
				'''
			}
		}
	}

	stage('Archive') { dir('output') {
		archiveArtifacts(
			artifacts: '**',
			fingerprint: true,
		)
	} }
}
