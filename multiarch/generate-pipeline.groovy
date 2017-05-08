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

node('master') {
	stage('Generate') {
		def dsl = ''

		for (arch in vars.arches) {
			def archImages = vars.archImages(arch)
			def ns = vars.archNamespace(arch)
			dsl += """
				folder('${arch}')
			"""
			for (img in archImages) {
				def imageMeta = vars.imagesMeta[img]
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
