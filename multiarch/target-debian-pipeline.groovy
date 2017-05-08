// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc

env.DPKG_ARCH = vars.dpkgArches[env.ACT_ON_ARCH]
if (!env.DPKG_ARCH) {
	error("Unknown 'dpkg' architecture for '${env.ACT_ON_ARCH}'.")
}

env.TARGET_NAMESPACE = vars.archNamespace(env.ACT_ON_ARCH)

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/tianon/docker-brew-debian.git',
					name: 'origin',
					refspec: '+refs/heads/master:refs/remotes/origin/master',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CloneOption',
						honorRefspec: true,
						noTags: true,
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'brew',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		checkout(
			poll: false,
			changelog: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker/docker.git',
					name: 'origin',
					refspec: '+refs/heads/master:refs/remotes/origin/master',
				]],
				branches: [[name: '68a5336b61c8b252966b582f0e4d08c6ae0bdb63']], // https://github.com/moby/moby/tree/master/contrib  ||  https://github.com/moby/moby/commits/master/contrib
				extensions: [
					[
						$class: 'CloneOption',
						honorRefspec: true,
						noTags: true,
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'docker',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') {
		sh 'rm -f "./$ACT_ON_IMAGE"'

		dir('brew') {
			stage('Prep') {
				sh '''
					echo "$DPKG_ARCH" > arch
					echo "$TARGET_NAMESPACE/$ACT_ON_IMAGE" > repo

					ln -svfT ../docker/contrib/mkimage.sh ./mkimage.sh
				'''
			}
			// TODO parallelize?  pre-build artifacts elsewhere which are then consumed here?  (as in Ubuntu)
			stage('Update') { retry(3) { sh './update.sh' } }
			stage('Commit') {
				sh '''
					git config user.name 'Docker Library Bot'
					git config user.email 'github+dockerlibrarybot@infosiftr.com'

					git add -A .
					git commit -m "Update for $ACT_ON_ARCH"
				'''
			}
			stage('Generate') { sh './generate-stackbrew-library.sh > "../$ACT_ON_IMAGE"' }
			stage('Seed Cache') {
				sh '''
					# ensure the bashbrew cache directory exists, and has an initialized Git repo
					bashbrew from https://raw.githubusercontent.com/docker-library/official-images/master/library/hello-world > /dev/null

					# and fill it with our newly generated commit (so that "bashbrew build" can DTRT)
					git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:
				'''
			}
		}

		stage('Build') {
			sh '''
				bashbrew build "./$ACT_ON_IMAGE"
			'''
		}

		stage('Tag') {
			sh '''
				bashbrew tag --namespace "$TARGET_NAMESPACE" "./$ACT_ON_IMAGE"
			'''
		}

		stage('Push') {
			sh '''
				bashbrew push --namespace "$TARGET_NAMESPACE" "./$ACT_ON_IMAGE"
			'''
		}
	}
}
