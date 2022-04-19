properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		//cron('@daily'),
	]),
])

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

node('built-in') {
	stage('Generate') {
		def dsl = ''

		for (arch in ['library'] + vars.arches) {
			def namespace = vars.archNamespace(arch)
			def cron = (arch == 'library' ? 'H * * * *' : 'H H/4 * * *')
			dsl += """
				pipelineJob('${arch}') {
					description('''
						<ul>
							<li><a href="https://hub.docker.com/u/${namespace}/"><code>docker.io/${namespace}</code></a></li>
							<li><a href="https://hub.docker.com/u/${namespace}/dashboard/"><code>docker.io/${namespace}</code> (dashboard)</a></li>
							<li><a href="https://github.com/docker-library/docs/tree/${arch}"><code>github.com/d-l/docs/${arch}</code> (committed docs)</a></li>
						</ul>
					''')
					logRotator {
						daysToKeep(14)
					}
					// TODO concurrentBuild(false)
					// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
					configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
					triggers {
						cron('${cron}')
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
								scriptPath('docs/target-pipeline.groovy')
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
