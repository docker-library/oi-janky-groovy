properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		//cron('H H * * *'),
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

node('master') {
	stage('Generate') {
		def dsl = ''

		for (arch in vars.arches) {
			def archImages = vars.archImages(arch)
			dsl += """
				folder('${arch}')
			"""
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

		dsl += '''
			nestedView('arches') {
				columns {
					status()
					weather()
				}
				views {
		'''
		for (arch in vars.arches) {
			dsl += """
					listView('${arch}') {
						jobs {
							regex('${arch}/.*')
						}
						filterBuildQueue()
						filterExecutors()
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
			nestedView('images') {
				columns {
					status()
					weather()
				}
				views {
		'''
		for (image in vars.images) {
			dsl += """
					listView('${image}') {
						jobs {
							regex('.*/${image}')
						}
						filterBuildQueue()
						filterExecutors()
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

		jobDsl(
			lookupStrategy: 'SEED_JOB',
			removedJobAction: 'DELETE',
			removedViewAction: 'DELETE',
			scriptText: dsl,
		)
	}
}
