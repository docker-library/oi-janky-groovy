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
		checkout(
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/perl-bashbrew.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'perl',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	env.REPOS = sh(returnStdout: true, script: '''
		heavyRegex="$(grep -vE '^$|^#' oi/heavy-hitters.txt | paste -sd '|')"
		bashbrew list --all --repos \\
			| grep -E "^($heavyRegex)(:|\\$)"
	''').trim()

	stash(
		name: 'library',
		includes: [
			'oi/library/**',
			'perl/**',
		].join(','),
	)
}

repos = env.REPOS.tokenize()
for (repo in repos) {
	env.REPO = repo

	node {
		unstash 'library'
		env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

		withCredentials([string(credentialsId: 'dockerhub-public-proxy', variable: 'DOCKERHUB_PUBLIC_PROXY')]) {
			stage(repo) {
				env.DRY_RUN = sh(returnStdout: true, script: '''
					perl/put-multiarch.sh --dry-run "$PUSH_TO_NAMESPACE/$REPO"
				''').trim()

				if (env.DRY_RUN != '') {
					if (0 != sh(returnStatus: true, script: '''
						perl/put-multiarch.sh "$PUSH_TO_NAMESPACE/$REPO"
					''')) {
						currentBuild.result = 'UNSTABLE'
					}
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
