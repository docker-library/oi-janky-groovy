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
		def dsl = ''
		for (arch in vars.arches) {
			def archImages = vars.archImages(arch)
			if (archImages.size() > 0) {
				dsl += """
					folder('${arch}')
				"""
			}
			for (img in archImages) {
				def imageMeta = vars.imagesMeta[img]
				dsl += """
					pipelineJob('${arch}/${img}') {
						logRotator { daysToKeep(14) }
						concurrentBuild(false)
						triggers {
							//cron('H H * * *')
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
		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
