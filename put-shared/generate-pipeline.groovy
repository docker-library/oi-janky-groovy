properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
	]),
])

node {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

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

	def dsl = ''

	stage('Generate') {
		def images = sh(returnStdout: true, script: '''
			heavyRegex="$(grep -vE '^$|^#' oi/heavy-hitters.txt | paste -sd '|')"
			bashbrew list --all --repos | grep -vE "^($heavyRegex)(:|\\$)"
		''').trim().tokenize()

		dsl += '''
			def images = [
		'''
		for (image in images) {
			dsl += """
				'${image}',
			"""
		}
		dsl += '''
			]

			for (image in images) {
				folder('light')
				pipelineJob("light/${image}") {
					logRotator { daysToKeep(14) }
					// TODO concurrentBuild(false)
					// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
					configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
					triggers {
						cron('H H/2 * * *')
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
								scriptPath('put-shared/target-pipeline.groovy')
							}
						}
					}
					configure {
						it / definition / lightweight(true)
					}
				}
			}
		'''
	}

	stage('Echo') {
		echo(dsl)
	}

	stage('DSL') {
		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
