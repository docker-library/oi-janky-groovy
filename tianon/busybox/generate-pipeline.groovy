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
	'arm32v5',
	'arm32v6',
	'arm32v7',
	'arm64v8',
	'i386',
	'ppc64le',
	'riscv64',
	's390x',
] as Set

node('built-in') {
	stage('Generate') {
		def dsl = ''

		for (arch in arches) {
			dsl += """
				pipelineJob('${arch}') {
					description('<a href="https://github.com/docker-library/busybox/tree/dist-${arch}"><code>docker-library/busybox</code> @ <code>dist-${arch}</code></a>')
					logRotator { numToKeep(10) }
					// TODO concurrentBuild(false)
					// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
					configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
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
				// TODO concurrentBuild(false)
				// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
				configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
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
