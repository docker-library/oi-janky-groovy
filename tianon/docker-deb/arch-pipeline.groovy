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

node(multiarchVars.node(env.ACT_ON_ARCH, 'sbuild')) { ansiColor('xterm') {
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

	dir('sources') {
		stage('Download') {
			sh '''
				wget -O sources.zip 'https://doi-janky.infosiftr.net/job/tianon/job/docker-deb/job/source/lastSuccessfulBuild/artifact/*_source.changes/*zip*/archive.zip'
				unzip sources.zip
				rm sources.zip
			'''
		}
	}

	// "findFiles" causes "java.io.NotSerializableException: java.util.AbstractList$Itr" (intermittently)
	changesFiles = sh(returnStdout: true, script: '''#!/usr/bin/env bash
		set -Eeuo pipefail
		shopt -s nullglob
		cd sources
		echo *_source.changes
	''').tokenize()
	if (!changesFiles) {
		error('No files matching "*_source.changes" found!')
	}

	dir('tianon-dockerfiles/sbuild') {
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
	}

	for (changesFile in changesFiles) {
		changesFile = changesFile as String // convert "org.jenkinsci.plugins.pipeline.utility.steps.fs.FileWrapper" to String
		dscFile = changesFile.replaceAll(/_source[.]changes$/, '.dsc')

		// "readFile" causes "java.io.NotSerializableException: java.util.AbstractList$Itr" (intermittently)
		suite = sh(returnStdout: true, script: """
			awk -F ': ' '\$1 == "Distribution" { print \$2; exit }' 'sources/${changesFile}'
		""").trim()
		if (!suite) {
			error("Failed to determine suite for '${changesFile}'!")
		}

		if (suite == 'xenial' || suite == 'zesty') {
			// TODO ubuntu
			continue
		}

		withEnv([
			'CHANGES_URL=' + 'https://doi-janky.infosiftr.net/job/tianon/job/docker-deb/job/source/lastSuccessfulBuild/artifact/' + changesFile,
			'DSC=' + dscFile,
			'SUITE=' + suite,
		]) {
			stage(suite) {
				sh '''
					docker run -i --rm \\
						-e CHANGES_URL -e DSC -e SUITE \\
						-v "$PWD":/work \\
						-w /work \\
						-e CHOWN="$(id -u):$(id -g)" \\
						--cap-add SYS_ADMIN \\
						tianon/sbuild bash -c '
							set -Eeuo pipefail
							set -x

							dpkgArch="$(dpkg --print-architecture)"
							targetDir="output/pool/$SUITE/main/$dpkgArch"

							mkdir -p "$targetDir"
							# attempt to avoid "java.nio.file.AccessDeniedException" on failed builds (root-owned files)
							# (see "dir(output) { deleteDir }" above)
							chown -R "$CHOWN" output
							chmod g+srwX output

							(
								cd "$targetDir"
								dget -du "$CHANGES_URL"
								[ -f "$DSC" ]
								chown -R "$CHOWN" .
							)

							# TODO handle ubuntu
							download-debuerreotype-tarball.sh "$SUITE" "$dpkgArch"

							sbuildArgs=(
								--verbose
								--dist "$SUITE"
								--arch "$dpkgArch"
								--no-source
								--arch-any
								--no-arch-all
							)
							# TODO figure out an OK way to handle arch:all packages (no need yet)
							# (need to go into "output/pool/$SUITE/main/all/")

							case "$SUITE" in
								jessie)
									sbuildArgs+=(
										--build-dep-resolver aptitude
										--extra-repository "deb http://deb.debian.org/debian ${SUITE}-backports main"
									)
									;;
							esac

							cd "$targetDir"
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
} }
