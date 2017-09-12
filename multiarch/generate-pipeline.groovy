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

		def fakedImages = []
		def images = []

		for (arch in vars.arches) {
			def archImages = sh(returnStdout: true, script: """#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x
				bashbrew cat --format '{{ range .Entries }}{{ if .HasArchitecture "${arch}" }}{{ \$.RepoName }}{{ "\\n" }}{{ end }}{{ end }}' --all
			""").trim().tokenize()
			def fakedArchImages = vars.archImages(arch)

			images += archImages
			fakedImages += fakedArchImages

			archImages += fakedArchImages
			archImages = archImages as Set

			def ns = vars.archNamespace(arch)
			dsl += """
				folder('${arch}')
			"""
			for (img in archImages) {
				def imageMeta = [:] + vars.imagesMeta[img] ?: [
					'pipeline': 'multiarch/target-generic-pipeline.groovy',
					'scmPolling': true,
				]
				if (arch == 'amd64') {
					// amd64 MUST always use the generic pipeline, regardless of any other faking
					imageMeta['pipeline'] = 'multiarch/target-generic-pipeline.groovy'
				}
				def triggers = []
				if (imageMeta['cron']) {
					triggers << "cron('${imageMeta['cron']}')"
				}
				if (imageMeta['scmPolling']) {
					triggers << "scm('@daily')"
				}
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
			}
		}

		fakedImages = fakedImages as Set
		images = images as Set

		for (imageList in [
			['faked-images', fakedImages],
			['images', images],
		]) {
			name = imageList[0]
			images = imageList[1]
			dsl += """
				nestedView('${name}') {
					columns {
						status()
						weather()
					}
					views {
			"""
			for (image in images) {
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
		}

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
