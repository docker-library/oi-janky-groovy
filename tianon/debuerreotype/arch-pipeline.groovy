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
	'tianon/debuerreotype/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "amd64", "arm64v8", etc.
env.ACT_ON_IMAGE = 'debuerreotype'
env.TARGET_NAMESPACE = multiarchVars.archNamespace(env.ACT_ON_ARCH)
env.BUILD_ARCH = vars.buildArch[env.ACT_ON_ARCH] ?: env.ACT_ON_ARCH

env.DPKG_ARCH = multiarchVars.dpkgArches[env.ACT_ON_ARCH]
if (!env.DPKG_ARCH) {
	error("Unknown 'dpkg' architecture for '${env.ACT_ON_ARCH}'.")
}

env.debuerreotypeVersion = vars.debuerreotypeVersion
env.TZ = 'UTC'

node(multiarchVars.node(env.BUILD_ARCH, env.ACT_ON_IMAGE)) {
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
					sed -ri "s!^dockerImage=.*\\$!dockerImage='debuerreotype/debuerreotype:${debuerreotypeVersion}-${ACT_ON_ARCH}'!" build*.sh
				'''
			}
		}

		env.epoch = sh(returnStdout: true, script: 'date --date "$timestamp" +%s').trim()
		env.serial = sh(returnStdout: true, script: 'date --date "@$epoch" +%Y%m%d').trim()
		iso8601 = sh(returnStdout: true, script: 'date --date "@$epoch" --iso-8601=seconds').trim()

		currentBuild.displayName = env.serial + ' (#' + currentBuild.number + ')'
		currentBuild.description = '<code>@' + env.epoch + '</code><br /><code>' + iso8601 + '</code>'

		env.targetDir = env.WORKSPACE + '/debian'
		env.artifactsDir = env.targetDir + '/' + env.serial + '/' + env.DPKG_ARCH
		dir(env.targetDir) {
			deleteDir()

			stage('Build') {
				retry(3) {
					sh '''
						mkdir -p "$artifactsDir"
						echo "$debuerreotypeVersion" > "$artifactsDir/debuerreotype-version"
						echo "$serial" > "$artifactsDir/serial"
						echo "$DPKG_ARCH" > "$artifactsDir/dpkg-arch"

						"$debuerreotypeDir/build-all.sh" . "@$epoch"
					'''
				}
			}
		}
		dir(env.artifactsDir) {
			stage('Archive') {
				archiveArtifacts(
					artifacts: '**',
					fingerprint: true,
				)
			}
		}
	}
}
