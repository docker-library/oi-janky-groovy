// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def multiarchVars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)
def vars = fileLoader.fromGit(
	'tianon/docker-deb/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "amd64", "arm64v8", etc.
env.BUILD_ARCH = vars.buildArch[env.ACT_ON_ARCH] ?: env.ACT_ON_ARCH

env.DPKG_ARCH = multiarchVars.dpkgArches[env.ACT_ON_ARCH]
if (!env.DPKG_ARCH) {
	error("Unknown 'dpkg' architecture for '${env.ACT_ON_ARCH}'.")
}

node(multiarchVars.node(env.BUILD_ARCH, 'sbuild')) { ansiColor('xterm') {
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

	dir('tianon-dockerfiles/sbuild') {
		stage('Pull') {
			sh '''
				gawk -v arch="$BUILD_ARCH" 'toupper($1) == "FROM" { $2 = arch "/" $2 } { print }' Dockerfile > Dockerfile.new
				mv Dockerfile.new Dockerfile
				gawk 'toupper($1) == "FROM" { print $2 }' Dockerfile \\
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

	// account for AppArmor
	env.DOCKER_FLAGS = sh(returnStdout: true, script: '''
		if docker info | grep -q apparmor; then
			echo --security-opt apparmor=unconfined
		fi
	''').tokenize().join(' ')

	dir('sources') {
		stage('Download') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail

				docker run -i --rm ${DOCKER_FLAGS:-} \\
					-v "$PWD":/work \\
					-w /work \\
					-u "$(id -u):$(id -g)" \\
					tianon/sbuild bash -c '
						set -Eeuo pipefail
						set -x

						wget -O sources.zip "https://doi-janky.infosiftr.net/job/tianon/job/docker-deb/job/source/lastSuccessfulBuild/artifact/*_source.changes/*zip*/archive.zip"
						unzip sources.zip
						rm sources.zip
					'
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

	for (changesFile in changesFiles) {
		changesFile = changesFile as String // convert "org.jenkinsci.plugins.pipeline.utility.steps.fs.FileWrapper" to String
		dscFile = changesFile.replaceAll(/_source[.]changes$/, '.dsc')

		// "readFile" causes "java.io.NotSerializableException: java.util.AbstractList$Itr" (intermittently)
		suite = sh(returnStdout: true, script: """
			gawk -F ': ' '\$1 == "Distribution" { print \$2; exit }' 'sources/${changesFile}'
		""").trim()
		if (!suite) {
			error("Failed to determine suite for '${changesFile}'!")
		}

		if (vars.exclusions[env.ACT_ON_ARCH] && vars.exclusions[env.ACT_ON_ARCH].contains(suite)) {
			// skip arch+suite combinations known not to work
			continue
		}

		withEnv([
			'CHANGES_URL=' + 'https://doi-janky.infosiftr.net/job/tianon/job/docker-deb/job/source/lastSuccessfulBuild/artifact/' + changesFile,
			'DSC=' + dscFile,
			'SUITE=' + suite,
			'COMP=' + vars.component,
		]) {
			stage(suite) {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail
					set -x

					# Ubuntu doesn't publish official sbuild-ready tarballs, so we might need to create one
					export TARGET_TARBALL="sbuild-$SUITE-$DPKG_ARCH.tar"
					# if this pull fails, we're probably not building for an Ubuntu suite
					if docker pull "$ACT_ON_ARCH/ubuntu:$SUITE"; then
						targetImage="tianon/sbuild-target:$SUITE-$DPKG_ARCH"
						docker build -t "$targetImage" - <<-EOF
							FROM $ACT_ON_ARCH/ubuntu:$SUITE
							RUN apt-get update && apt-get install -y --no-install-recommends build-essential fakeroot && rm -rf /var/lib/apt/lists/*
						EOF
						targetContainer="sbuild-target-$SUITE-$DPKG_ARCH"
						trap "docker rm -vf '$targetContainer' || :" EXIT
						docker rm -vf "$targetContainer" || :
						docker create --name "$targetContainer" "$targetImage"
						docker export -o "$TARGET_TARBALL" "$targetContainer"
						docker rm -vf "$targetContainer"
						trap - EXIT
					fi

					docker run -i --rm ${DOCKER_FLAGS:-} \\
						-e CHANGES_URL -e DSC -e SUITE -e COMP -e DPKG_ARCH -e TARGET_TARBALL \\
						-v "$PWD":/work \\
						-w /work \\
						-e CHOWN="$(id -u):$(id -g)" \\
						--cap-add SYS_ADMIN \\
						tianon/sbuild bash -c '
							set -Eeuo pipefail
							set -x

							targetDir="output/pool/$SUITE/$COMP/$DPKG_ARCH"

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

							if [ -e "$TARGET_TARBALL" ]; then
								mv "$TARGET_TARBALL" "/tarballs/$TARGET_TARBALL"
								chown root:root "/tarballs/$TARGET_TARBALL" # schroot is picky about tarball ownership
								tee "/etc/schroot/chroot.d/$SUITE-$DPKG_ARCH-sbuild" <<-EOF
									[$SUITE-$DPKG_ARCH-sbuild]
									description=Ubuntu $SUITE/$DPKG_ARCH autobuilder
									groups=root,sbuild
									root-groups=root,sbuild
									profile=sbuild
									type=file
									file=/tarballs/$TARGET_TARBALL
									source-root-groups=root,sbuild
								EOF
							else
								download-debuerreotype-tarball.sh "$SUITE" "$DPKG_ARCH"
							fi

							sbuildArgs=(
								--verbose
								--dist "$SUITE"
								--arch "$DPKG_ARCH"
								--no-source
								--arch-any
								--no-arch-all
							)
							# TODO figure out an OK way to handle arch:all packages (no need yet)
							# (need to go into "output/pool/$SUITE/$COMP/all/")

							case "$SUITE" in
								jessie)
									sbuildArgs+=(
										--build-dep-resolver aptitude
										--extra-repository "deb http://deb.debian.org/debian ${SUITE}-backports main"
									)
									;;
								xenial)
									# btrfs-progs vs btrfs-tools
									sbuildArgs+=(
										--build-dep-resolver aptitude
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
