properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		//cron('@daily'),
	]),
])

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'tianon/debuerreotype/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

node('master') {
	stage('Generate') {
		def dsl = ''

		for (arch in vars.arches) {
			dsl += """
				pipelineJob('${arch}') {
					logRotator {
						numToKeep(10)
						artifactNumToKeep(3)
					}
					concurrentBuild(false)
					parameters {
						stringParam('timestamp', 'today 00:00:00', 'A string that "date(1)" can parse.')
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
								scriptPath('tianon/debuerreotype/arch-pipeline.groovy')
							}
						}
					}
					configure {
						it / definition / lightweight(true)
					}
				}
			"""
		}

		dsl += '''
			pipelineJob('_trigger') {
				logRotator { numToKeep(10) }
				concurrentBuild(false)
				parameters {
					stringParam('timestamp', 'today 00:00:00', 'A string that "date(1)" can parse.')
				}
				definition {
					cps {
						script("""
		'''
		for (arch in vars.arches) {
			dsl += """
				stage('Trigger ${arch}') {
					build(
						job: '${arch}',
						parameters: [
							string(name: 'timestamp', value: params.timestamp),
						],
						wait: false,
					)
				}
			"""
		}
		dsl += '''
						""")
						sandbox()
					}
				}
			}

			pipelineJob('_collector') {
				logRotator { numToKeep(10) }
				concurrentBuild(false)
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
							scriptPath('tianon/debuerreotype/collector-pipeline.groovy')
						}
					}
				}
				configure {
					it / definition / lightweight(true)
				}
			}
		'''

		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
