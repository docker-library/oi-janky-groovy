properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
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

def arches = vars.arches
env.PUSH_TO_NAMESPACE = 'library'

env.BASHBREW_ARCH_NAMESPACES = vars.archNamespaces()

node {
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
						includedRegions: 'library/.+',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	env.REPOS = sh(returnStdout: true, script: '''
		bashbrew list --all --repos \\
			| grep -E "^($(grep -vE '^$|^#' oi/heavy-hitters.txt | paste -sd '|'))(:|\\$)"
	''').trim()

	stash(
		name: 'library',
		includes: 'oi/library/**',
	)
}

repos = env.REPOS.tokenize()
for (repo in repos) {
	env.REPO = repo

	node {
		unstash 'library'
		env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

		stage(repo) {
			env.DRY_RUN = sh(returnStdout: true, script: '''
				bashbrew put-shared --dry-run --namespace "$PUSH_TO_NAMESPACE" "$REPO"
			''').trim()

			if (env.DRY_RUN != '') {
				if (0 != sh(returnStatus: true, script: '''
					bashbrew put-shared --namespace "$PUSH_TO_NAMESPACE" "$REPO"
				''')) {
					currentBuild.result = 'UNSTABLE'
				}
			}
		}
	}

	stage('Sleep') {
		if (env.DRY_RUN != '') {
			sleep(
				time: 15,
				unit: 'MINUTES',
			)
		}
	}
}
