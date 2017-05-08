properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		//cron('H H * * *'),
	]),
])

node('master') {
	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/oi-janky-groovy.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi-janky-groovy',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'multiarch',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	def vars = load('oi-janky-groovy/multiarch/vars.groovy')

	stage('Generate') {
		for (arch in vars.arches) {
			def archImages = vars.archImages(arch)
			for (img in archImages) {
				def imageMeta = vars.imagesMeta[img]
				if (fileExists('oi-janky-groovy/' + imageMeta['pipeline'])) {
					echo("${arch}/${img} => ${imageMeta['pipeline']}")
				}
				else {
					echo("\n\nWARNING: '${imageMeta['pipeline']}' does not exist! (needed for '${img}')\n\n")
				}
			}
		}
		error('TODO WIP')
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
