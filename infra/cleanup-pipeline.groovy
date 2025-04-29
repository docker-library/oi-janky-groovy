// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def infraVars = fileLoader.fromGit(
	'infra/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

properties([
	// make sure there is enough for every arch triggered by an all-cleanup
	buildDiscarder(logRotator(numToKeepStr: (infraVars.archWorkers.size() * 2).toString())),
	disableResume(),
	parameters([
		choice(
			name: 'TARGET_NODE',
			choices: infraVars.archWorkers,
			description: 'which node to "cleanup" (list is hand-maintained 😭)',
		),
		string(
			name: 'BASHBREW_ARCH',
			defaultValue: '',
			description: 'will be auto-detected properly when unspecified (do not specify unless something is not working for some reason)',
			trim: true,
		),
	]),
	//pipelineTriggers([cron('H H * * 0')]), // this should be on the triggering job instead, not here
])

if (params.BASHBREW_ARCH) {
	env.BASHBREW_ARCH = params.BASHBREW_ARCH
} else {
	if (params.TARGET_NODE.startsWith('multiarch-')) {
		env.BASHBREW_ARCH = params.TARGET_NODE - 'multiarch-'
	} else if (params.TARGET_NODE.startsWith('windows-')) {
		env.BASHBREW_ARCH = 'windows-amd64' // TODO non-amd64??  match the other naming scheme, probably
	} else if (params.TARGET_NODE.startsWith('worker-')) {
		env.BASHBREW_ARCH = 'amd64' // all our workers are amd64 -- if that ever changes, we've probably got all kinds of assumptions we need to clean up! 😅
		// TODO ideally, this would somehow lock the *entire* worker instance until the cleanup job completes, but for now we'll have to live with slightly racy behavior (and rely on our other jobs being forgiving / able to do the correct thing on a re-run if they fail due to over-aggressive cleanup)
	} else {
		error('BASHBREW_ARCH not specified and unable to infer from TARGET_NODE (' + params.TARGET_NODE + ') 😞')
	}
}
echo('BASHBREW_ARCH: ' + env.BASHBREW_ARCH)

currentBuild.displayName = params.TARGET_NODE + ' <' + env.BASHBREW_ARCH + '> (#' + currentBuild.number + ')'

node(params.TARGET_NODE) {
	ansiColor('xterm') {

		stage('Containers') {
			sh 'docker container prune --force'
		}

		stage('Volumes') {
			sh 'docker volume prune --all --force'
		}

		stage('Images') {
			// we only use classic builder, so we don't need to save local images
			sh 'docker image prune --all --force'
		}

		// TODO somehow clean up BASHBREW_CACHE ?
	}
}
