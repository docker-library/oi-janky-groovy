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
						includedRegions: 'library/**',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	stage('Generate') {
		def dsl = ''

		def allImages = []

		for (arch in vars.arches) {
			def archImages = sh(returnStdout: true, script: """#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x
				bashbrew cat --format '{{ range .Entries }}{{ if .HasArchitecture "${arch}" }}{{ \$.RepoName }}{{ "\\n" }}{{ end }}{{ end }}' --all
			""").trim().tokenize()

			archImages += vars.archImages(arch)
			archImages = archImages as Set

			def ns = vars.archNamespace(arch)
			dsl += """
				folder('${arch}')
			"""
			for (img in archImages) {
				def imageMeta = vars.imagesMeta[img] ?: [
					'pipeline': 'multiarch/target-generic-pipeline.groovy',
				]
				def triggers = []
				if (imageMeta['cron']) {
					triggers << "cron('${imageMeta['cron']}')"
				}
				// TODO more triggers, especially SCM-based triggers (if we can get them working sanely)
				dsl += """
					pipelineJob('${arch}/${img}') {
						description('''
							Useful links:
							<ul>
								<li><a href="https://hub.docker.com/r/${ns}/${img}/"><code>docker.io/${ns}/${img}</code></a></li>
								<li><a href="https://hub.docker.com/_/${img}/"><code>docker.io/library/${img}</code></a></li>
								<li><a href="https://github.com/docker-library/official-images/blob/master/library/${img}"><code>oi/library/${img}</code></a></li>
							</ul>
						''')
						logRotator { daysToKeep(14) }
						concurrentBuild(false)
						triggers { ${triggers.join('\n')} }
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
									scriptPath('${imageMeta['pipeline']}')
								}
							}
						}
						configure {
							it / definition / lightweight(true)
						}
					}
				"""
				// "fileExists" throws annoying exceptions ("java.io.NotSerializableException: java.util.LinkedHashMap$LinkedKeyIterator")

				allImages << img
			}
		}

		allImages = allImages as Set

		dsl += '''
			nestedView('images') {
				columns {
					status()
					weather()
				}
				views {
		'''
		for (image in allImages) {
			dsl += """
					listView('${image}') {
						jobs {
							regex('.*/${image}')
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
			"""
		}
		dsl += '''
				}
			}
		'''

		dsl += '''
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

		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
