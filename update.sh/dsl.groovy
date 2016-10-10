def vars = (new GroovyShell()).evaluate(readFileFromWorkspace('oi-janky-groovy/update.sh/vars.groovy'))

for (repo in vars.repos) {
	pipelineJob(repo) {
		logRotator { daysToKeep(4) }
		concurrentBuild(false)
		triggers {
			cron('H H/6 * * *')
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
							relativeTargetDirectory('oi-janky-groovy')
						}
					}
					scriptPath('oi-janky-groovy/update.sh/target-pipeline.groovy')
				}
			}
		}
	}
}
