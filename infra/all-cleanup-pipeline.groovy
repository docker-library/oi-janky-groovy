properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableResume(),
	pipelineTriggers([cron('H H * * 0')]),
])

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def infraVars = fileLoader.fromGit(
	'infra/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

node {
	for (worker in infraVars.archWorkers) {
		stage(worker) {
			build(
				job: 'cleanup',
				parameters: [
					string(
						name: 'TARGET_NODE',
						value: worker,
					),
				],
				wait: false,
			)
		}
	}
}
