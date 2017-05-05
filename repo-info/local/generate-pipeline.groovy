properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *')
	]),
])

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
					[$class: 'RelativeTargetDirectory', relativeTargetDir: 'oi'],
					[$class: 'CleanCheckout'],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	def repos = []
	stage('Gather') {
		repos = sh(
			returnStdout: true,
			script: '''
				export BASHBREW_LIBRARY="$PWD/oi/library"
				bashbrew list --all --repos
			''',
		).trim().tokenize('\n')
	}

	stage('Generate') {
		def dsl = ''
		for (repo in repos) {
			dsl += """
				pipelineJob('${repo}') {
					logRotator { daysToKeep(4) }
					concurrentBuild(false)
					triggers {
						cron('H H * * H/3')
					}
					definition {
						cpsScm {
							scm {
								git {
									remote {
										url('https://github.com/docker-library/oi-janky-groovy.git')
									}
									branch('*/master')
									extensions {
										cleanAfterCheckout()
									}
								}
								scriptPath('repo-info/local/target-pipeline.groovy')
							}
						}
					}
					configure {
						it / definition / lightweight(true)
					}
				}
			"""
		}
		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
