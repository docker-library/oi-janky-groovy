properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		//cron('@daily'),
	]),
])

// arches of both Debian (glibc) and Alpine (musl)
// arches of uClibc will be a subset of these
arches = [
	'amd64',
	//'arm32v5', // TODO get another builder for arm32v5
	'arm32v6',
	'arm32v7',
	'arm64v8',
	'i386',
	'ppc64le',
	's390x',
] as Set

node('master') {
	stage('Generate') {
		def dsl = ''

		for (arch in arches) {
			dsl += """
				pipelineJob('${arch}') {
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
								scriptPath('tianon/busybox/arch-pipeline.groovy')
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
				definition {
					cps {
						script("""
		'''
		for (arch in arches) {
			dsl += """
				stage('Trigger ${arch}') {
					build(
						job: '${arch}',
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
		'''

		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
