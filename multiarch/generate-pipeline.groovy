properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * *'),
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

	def dsl = '''
		def arches = []
		def archNamespaces = [:]
		def archImages = [:]
	'''

	for (arch in vars.arches) {
		stage(arch) {
			def archImages = sh(returnStdout: true, script: """#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x
				bashbrew cat --format '{{ range .Entries }}{{ if .HasArchitecture "${arch}" }}{{ \$.RepoName }}{{ "\\n" }}{{ end }}{{ end }}' --all \\
					| sort -u
			""").trim().tokenize()

			def ns = vars.archNamespace(arch)
			dsl += """
				arch = '${arch}'
				arches += arch
				archNamespaces[arch] = '${ns}'
				archImages[arch] = [
					'_trigger',
			"""
			for (img in archImages) {
				dsl += """
					'${img}',
				"""
			}
			dsl += '''
				] as Set
			'''
		}
	}

	stage('Generate') {
		dsl += '''
			def images = []

			for (arch in arches) {
				folder(arch)

				def ns = archNamespaces[arch]

				for (img in archImages[arch]) {
					images += img

					if (img == '_trigger') {
						desc = 'Trigger all the things! (Jenkins SCM triggering does not work well for our use case)'
						groovyScript = 'multiarch/trigger-pipeline.groovy'
					} else {
						desc = """
							Useful links:
							<ul>
								<li><a href="https://hub.docker.com/r/${ns}/${img}/"><code>docker.io/${ns}/${img}</code></a></li>
								<li><a href="https://hub.docker.com/_/${img}/"><code>docker.io/library/${img}</code></a></li>
								<li><a href="https://github.com/docker-library/official-images/blob/master/library/${img}"><code>official-images/library/${img}</code></a></li>
							</ul>
						"""
						groovyScript = 'multiarch/target-pipeline.groovy'
					}

					pipelineJob("${arch}/${img}") {
						description(desc)
						logRotator { daysToKeep(14) }
						// TODO concurrentBuild(false)
						// see https://issues.jenkins-ci.org/browse/JENKINS-31832?focusedCommentId=343307&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-343307
						configure { it / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' { } }
						triggers {
							if (img == '_trigger') {
								if (arch == 'amd64') {
									cron('@hourly')
								}
								else {
									cron('H H(0-5) * * *')
								}
							}
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
									scriptPath(groovyScript)
								}
							}
						}
						configure {
							it / definition / lightweight(true)
						}
					}
				}
			}

			images = images as Set

			nestedView('images') {
				columns {
					status()
					weather()
				}
				views {
					for (image in images) {
						listView(image) {
							jobs {
								regex(".*/${image}")
							}
							recurse()
							columns {
								status()
								weather()
								name()
								lastSuccess()
								lastFailure()
								lastDuration()
								nextLaunch()
								buildButton()
							}
						}
					}
				}
			}

			listView('flat') {
				jobs {
					regex('.*')
				}
				recurse()
				columns {
					status()
					weather()
					name()
					lastSuccess()
					lastFailure()
					lastDuration()
					nextLaunch()
					buildButton()
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
