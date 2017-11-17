properties([
	buildDiscarder(logRotator(daysToKeepStr: '14')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H * * * *'),
	]),
])

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "library", "i386", etc
isLibrary = (env.ACT_ON_ARCH == 'library')
if (isLibrary) {
	// normalize such that ACT_ON_ARCH
	env.ACT_ON_ARCH = 'amd64'
}
env.TARGET_NAMESPACE = (isLibrary ? 'library' : vars.archNamespace(env.ACT_ON_ARCH))
env.ACT_ON_IMAGE = 'docker-library-docs:' + (isLibrary ? 'latest' : env.ACT_ON_ARCH)

node(vars.docsNode(env.ACT_ON_ARCH, 'docs')) {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	env.BASHBREW_ARCH_NAMESPACES = vars.archNamespaces()
	if (!isLibrary) {
		env.BASHBREW_ARCH = env.ACT_ON_ARCH
	}

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:docker-library/docs.git',
					credentialsId: 'docker-library-bot',
					name: 'origin',
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
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: [
							'library/.*',
						].join('\n'),
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') { dir('d') {
		stage('Update') {
			if (isLibrary) {
				sh './update.sh'
			} else {
				sh '''
					# add a link to Jenkins build status
					if false; then
						cat >> .template-helpers/generate-dockerfile-links-partial.sh <<-'EOSH'

							cat <<-EOBADGE
								[![Build Status](${JOB_URL%/}/badge/icon) (\\`$TARGET_NAMESPACE/$1\\` build job)](${JOB_URL})
							EOBADGE
						EOSH
						# TODO these are now no good since JOB_URL will point to the docs job, not the build job!
						# need to embed this behavior directly in the docs repo now (with generated links instead)
					fi

					./update.sh --namespace "$TARGET_NAMESPACE"
				'''
			}
		}

		// TODO decide whether to push the multiarch docs to a branch-per-arch
		if (isLibrary) {
			stage('Commit') {
				sh('''
					git config user.name 'Docker Library Bot'
					git config user.email 'github+dockerlibrarybot@infosiftr.com'

					git add . || :
					git commit -m 'Run update.sh' || :
				''')
			}

			sshagent(['docker-library-bot']) {
				stage('Push') {
					sh('''
						git push origin HEAD:master
					''')
				}
			}
		}

		withCredentials([[
			$class: 'UsernamePasswordMultiBinding',
			credentialsId: 'docker-hub-' + (isLibrary ? 'stackbrew' : env.ACT_ON_ARCH.replaceAll(/^[^-]+-/, '')),
			usernameVariable: 'USERNAME',
			passwordVariable: 'PASSWORD',
		]]) {
			stage('Deploy') {
				sh('''
					docker build --pull -t "$ACT_ON_IMAGE" .
					test -t 1 && it='-it -e TERM' || it='-i'
					set +x
					docker run "$it" --rm \
						--entrypoint './push.pl' \
						"$ACT_ON_IMAGE" \
						--username "$USERNAME" \
						--password "$PASSWORD" \
						--namespace "$TARGET_NAMESPACE" \
						--batchmode */
				''')
			}
		}
	} }
}
