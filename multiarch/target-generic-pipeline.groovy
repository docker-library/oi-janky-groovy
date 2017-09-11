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

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	env.BASHBREW_ARCH = env.ACT_ON_ARCH

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
	}

	ansiColor('xterm') {
		vars.pullFakeFroms(this)

		env.BAP_RESULT = vars.bashbrewBuildAndPush(this)

		if (env.BAP_RESULT != 'skip') {
			stage('Trigger Children') {
				children = sh(returnStdout: true, script: '''
					bashbrew children --apply-constraints --depth 1 "$ACT_ON_IMAGE" \\
						| grep -vE '^'"$ACT_ON_IMAGE"'(:|$)' \\
						| cut -d: -f1 \\
						| sort -u
				''').trim().tokenize()
				for (child in children) {
					build(
						job: child,
						quietPeriod: 15 * 60, // 15 minutes
						wait: false,
					)
				}
			}

			vars.stashBashbrewBits(this)
		}
	}
}

if (env.BAP_RESULT != 'skip') {
	vars.docsBuildAndPush(this)
}
