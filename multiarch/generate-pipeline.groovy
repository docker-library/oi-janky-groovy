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

	stage('Generate') {
		def dsl = '''
			def arches = []
			def archImages = [:]
			def archNamespaces = [:]

			def images = []
		'''
		for (arch in vars.arches) {
			def archImages = sh(returnStdout: true, script: """#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x
				bashbrew cat --format '{{ range .Entries }}{{ if .HasArchitecture "${arch}" }}{{ \$.RepoName }}{{ "\\n" }}{{ end }}{{ end }}' --all
			""").trim().tokenize()

			def ns = vars.archNamespace(arch)
			dsl += """
				arch = '${arch}'
				arches += arch
				archImages[arch] = []
				archNamespaces[arch] = '${ns}'
			"""
			for (img in archImages) {
				dsl += """
					img = '${img}'
					archImages[arch] += img
					images += img
				"""
			}
			dsl += '''
				archImages[arch] = archImages[arch] as Set
			'''
		}
		dsl += '''
			images = images as Set

			for (arch in arches) {
				def ns = archNamespaces[arch]

				for (img in archImages[arch]) {
					def triggers = []
					if (arch == 'amd64') {
						triggers << "scm('@hourly')"
					}
					else {
						triggers << "scm('@daily')"
					}

					pipelineJob('${arch}/${img}') {
						description("""
							Useful links:
							<ul>
								<li><a href="https://hub.docker.com/r/${ns}/${img}/"><code>docker.io/${ns}/${img}</code></a></li>
								<li><a href="https://hub.docker.com/_/${img}/"><code>docker.io/library/${img}</code></a></li>
								<li><a href="https://github.com/docker-library/official-images/blob/master/library/${img}"><code>official-images/library/${img}</code></a></li>
							</ul>
						""")
						logRotator { daysToKeep(14) }
						concurrentBuild(false)
						triggers { ${triggers.join('\\n')} }
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
									scriptPath('multiarch/target-pipeline.groovy')
								}
							}
						}
						configure {
							it / definition / lightweight(true)
						}
					}
				}
			}

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

		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
